package dev.kaee.minikv.engine.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Append-only log split into numbered segment files.
 *
 * <p>Positions are logical byte offsets across all segments written by this instance. A writer
 * appends under {@code writeLock}, then asks the {@link SyncPolicy} to wait until the log is
 * durable up to its end position.
 *
 * <p>Group commit is leader/follower. A writer that finds its position not yet durable and no fsync
 * running becomes the leader: it snapshots the appended position, releases {@code syncLock},
 * fsyncs, then wakes everyone. Writers that arrive meanwhile wait on a condition rather than
 * queueing for the lock, so when the fsync ends they all see the new durable position at once, and
 * one of those still uncovered leads the next fsync on behalf of all of them. A writer whose
 * position is already durable returns without taking any lock.
 *
 * <p>Lock order is {@code syncLock} then {@code writeLock}. Appends take only {@code writeLock}. A
 * failed fsync leaves the file's durable state unknown, so it poisons the log: every later
 * durability wait fails.
 */
public final class WriteAheadLog implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger(WriteAheadLog.class.getName());
  private static final Pattern SEGMENT_NAME = Pattern.compile("wal-(\\d{20})\\.log");

  private final Path dir;
  private final SyncPolicy policy;
  private final ReentrantLock writeLock = new ReentrantLock();
  private final ReentrantLock syncLock = new ReentrantLock();
  private final Condition syncDone = syncLock.newCondition();
  private final AtomicLong fsyncs = new AtomicLong();
  private final List<Long> liveSegments = new ArrayList<>();

  // Guarded by writeLock.
  private FileChannel current;
  private long currentId;
  private long appendedPosition;
  private long appendedRecords;
  private boolean closed;

  // Written under syncLock; read without it on the fast path.
  private volatile long durablePosition;

  // Guarded by syncLock.
  private boolean forceInProgress;
  private IOException syncFailure;

  private WriteAheadLog(Path dir, SyncPolicy policy, List<Long> existing, long firstId)
      throws IOException {
    this.dir = dir;
    this.policy = policy;
    this.liveSegments.addAll(existing);
    this.currentId = firstId;
    this.current = createSegment(firstId);
    this.liveSegments.add(firstId);
  }

  /**
   * Opens a log that writes to a new segment numbered after every existing one. Run recovery with
   * {@link WalReplay} first; recovered segments are never appended to.
   */
  public static WriteAheadLog open(Path dir, SyncPolicy policy) throws IOException {
    return open(dir, policy, 0);
  }

  /**
   * Like {@link #open(Path, SyncPolicy)}, but the new segment id is also greater than {@code
   * retiredThrough}, so ids stay increasing after old segments are deleted.
   */
  public static WriteAheadLog open(Path dir, SyncPolicy policy, long retiredThrough)
      throws IOException {
    Files.createDirectories(dir);
    List<Long> existing = listSegments(dir);
    long highest = existing.isEmpty() ? 0 : existing.get(existing.size() - 1);
    long next = Math.max(highest, retiredThrough) + 1;
    WriteAheadLog wal = new WriteAheadLog(dir, policy, existing, next);
    policy.start(wal);
    return wal;
  }

  /** Appends one record and returns once it is durable under the configured policy. */
  public long append(WalRecord record) throws IOException {
    ByteBuffer buf = WalCodec.encode(record);
    long end;
    writeLock.lock();
    try {
      ensureOpen();
      while (buf.hasRemaining()) {
        current.write(buf);
      }
      appendedPosition += buf.limit();
      appendedRecords++;
      end = appendedPosition;
    } finally {
      writeLock.unlock();
    }
    policy.awaitDurable(this, end);
    return end;
  }

  /**
   * Closes the current segment and starts a new one. Returns the id of the closed segment, which
   * holds every record appended before this call.
   */
  public long rotate() throws IOException {
    syncLock.lock();
    try {
      awaitNoForce();
      writeLock.lock();
      try {
        ensureOpen();
        long closedId = currentId;
        if (policy.fsyncs()) {
          current.force(false);
          fsyncs.incrementAndGet();
        }
        durablePosition = appendedPosition;
        syncDone.signalAll();
        current.close();
        currentId++;
        current = createSegment(currentId);
        liveSegments.add(currentId);
        return closedId;
      } finally {
        writeLock.unlock();
      }
    } finally {
      syncLock.unlock();
    }
  }

  /**
   * Deletes every closed segment with id {@code <= segmentId}. Call only after the manifest durably
   * records that their data is in SSTables. The current segment is never deleted.
   */
  public void deleteSegmentsThrough(long segmentId) throws IOException {
    List<Long> doomed = new ArrayList<>();
    writeLock.lock();
    try {
      for (long id : liveSegments) {
        if (id <= segmentId && id != currentId) {
          doomed.add(id);
        }
      }
      liveSegments.removeAll(doomed);
    } finally {
      writeLock.unlock();
    }
    for (long id : doomed) {
      Files.deleteIfExists(segmentPath(dir, id));
    }
    if (!doomed.isEmpty()) {
      FileSync.fsyncDirectory(dir);
    }
  }

  // ALWAYS mode: lead an fsync or follow one until this position is durable.
  void syncTo(long position) throws IOException {
    if (durablePosition >= position) {
      return; // covered by an earlier fsync; no lock needed
    }
    syncLock.lock();
    try {
      while (durablePosition < position) {
        throwIfUnusable();
        if (forceInProgress) {
          awaitSyncDone();
        } else {
          leadForce();
        }
      }
    } finally {
      syncLock.unlock();
    }
  }

  // BATCH mode: wait for the background thread.
  void awaitSynced(long position) throws IOException {
    if (durablePosition >= position) {
      return;
    }
    syncLock.lock();
    try {
      while (durablePosition < position) {
        throwIfUnusable();
        awaitSyncDone();
      }
    } finally {
      syncLock.unlock();
    }
  }

  void backgroundSync() {
    syncLock.lock();
    try {
      if (!forceInProgress && syncFailure == null) {
        leadForce();
      }
    } catch (IOException e) {
      LOG.log(System.Logger.Level.ERROR, "WAL background fsync failed", e);
    } finally {
      syncLock.unlock();
    }
  }

  /**
   * Fsyncs everything appended so far. Caller holds {@code syncLock} and no force is running; the
   * lock is released for the duration of the fsync and held again on return.
   */
  private void leadForce() throws IOException {
    FileChannel ch;
    long target;
    writeLock.lock();
    try {
      if (closed) {
        return;
      }
      ch = current;
      target = appendedPosition;
    } finally {
      writeLock.unlock();
    }
    if (target <= durablePosition) {
      return;
    }
    // Rotation and close wait for forceInProgress to clear, so the channel stays open.
    forceInProgress = true;
    IOException failure = null;
    syncLock.unlock();
    try {
      ch.force(false);
      fsyncs.incrementAndGet();
    } catch (IOException e) {
      failure = e;
    } finally {
      syncLock.lock();
      forceInProgress = false;
      if (failure == null) {
        durablePosition = Math.max(durablePosition, target);
      } else {
        syncFailure = failure;
      }
      syncDone.signalAll();
    }
    if (failure != null) {
      throw failure;
    }
  }

  // Caller holds syncLock.
  private void awaitSyncDone() throws IOException {
    try {
      syncDone.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted waiting for WAL fsync", e);
    }
  }

  // Caller holds syncLock.
  private void awaitNoForce() throws IOException {
    while (forceInProgress) {
      awaitSyncDone();
    }
  }

  // Caller holds syncLock.
  private void throwIfUnusable() throws IOException {
    if (syncFailure != null) {
      throw new IOException("WAL fsync failed earlier; durability is unknown", syncFailure);
    }
    if (isClosed()) {
      throw new IOException("WAL closed before write became durable");
    }
  }

  /** Number of fsync calls on segment files. Fewer fsyncs than records shows group commit. */
  public long fsyncCount() {
    return fsyncs.get();
  }

  public long appendedRecords() {
    writeLock.lock();
    try {
      return appendedRecords;
    } finally {
      writeLock.unlock();
    }
  }

  public List<Long> liveSegments() {
    writeLock.lock();
    try {
      return List.copyOf(liveSegments);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void close() throws IOException {
    policy.stop();
    syncLock.lock();
    try {
      awaitNoForce();
      writeLock.lock();
      try {
        if (closed) {
          return;
        }
        if (policy.fsyncs() && syncFailure == null) {
          current.force(false);
          fsyncs.incrementAndGet();
        }
        durablePosition = appendedPosition;
        current.close();
        closed = true;
        syncDone.signalAll();
      } finally {
        writeLock.unlock();
      }
    } finally {
      syncLock.unlock();
    }
  }

  private boolean isClosed() {
    writeLock.lock();
    try {
      return closed;
    } finally {
      writeLock.unlock();
    }
  }

  private void ensureOpen() throws IOException {
    if (closed) {
      throw new IOException("WAL is closed");
    }
  }

  private FileChannel createSegment(long id) throws IOException {
    FileChannel ch =
        FileChannel.open(
            segmentPath(dir, id), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    FileSync.fsyncDirectory(dir);
    return ch;
  }

  public static Path segmentPath(Path dir, long id) {
    return dir.resolve("wal-" + String.format(Locale.ROOT, "%020d", id) + ".log");
  }

  /** Segment ids found in {@code dir}, ascending. */
  public static List<Long> listSegments(Path dir) throws IOException {
    List<Long> ids = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return ids;
    }
    try (var files = Files.list(dir)) {
      for (Path p : (Iterable<Path>) files::iterator) {
        Matcher m = SEGMENT_NAME.matcher(p.getFileName().toString());
        if (m.matches()) {
          ids.add(Long.parseLong(m.group(1)));
        }
      }
    }
    ids.sort(null);
    return ids;
  }
}
