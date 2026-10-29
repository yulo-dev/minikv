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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Append-only log split into numbered segment files.
 *
 * <p>Positions are logical byte offsets across all segments written by this instance. A writer
 * appends under {@code writeLock}, then asks the {@link SyncPolicy} to wait until the log is
 * durable up to its end position. An fsync always covers everything appended before it started,
 * which is what lets concurrent writers share one fsync.
 *
 * <p>Lock order is {@code syncMonitor} then {@code writeLock}. Appends take only {@code writeLock},
 * so they never wait behind an fsync in progress.
 */
public final class WriteAheadLog implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger(WriteAheadLog.class.getName());
  private static final Pattern SEGMENT_NAME = Pattern.compile("wal-(\\d{20})\\.log");

  private final Path dir;
  private final SyncPolicy policy;
  private final ReentrantLock writeLock = new ReentrantLock();
  private final Object syncMonitor = new Object();
  private final AtomicLong fsyncs = new AtomicLong();
  private final List<Long> liveSegments = new ArrayList<>();

  // Guarded by writeLock.
  private FileChannel current;
  private long currentId;
  private long appendedPosition;
  private long appendedRecords;
  private boolean closed;

  // Guarded by syncMonitor.
  private long durablePosition;
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
    Files.createDirectories(dir);
    List<Long> existing = listSegments(dir);
    long next = existing.isEmpty() ? 1 : existing.get(existing.size() - 1) + 1;
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
    synchronized (syncMonitor) {
      writeLock.lock();
      try {
        ensureOpen();
        long closedId = currentId;
        if (policy.fsyncs()) {
          current.force(false);
          fsyncs.incrementAndGet();
        }
        durablePosition = appendedPosition;
        syncMonitor.notifyAll();
        current.close();
        currentId++;
        current = createSegment(currentId);
        liveSegments.add(currentId);
        return closedId;
      } finally {
        writeLock.unlock();
      }
    }
  }

  // ALWAYS mode: fsync unless another writer's fsync already covered this position.
  void syncTo(long position) throws IOException {
    synchronized (syncMonitor) {
      if (durablePosition >= position) {
        return;
      }
      forceLocked();
    }
  }

  // BATCH mode: wait for the background thread.
  void awaitSynced(long position) throws IOException {
    synchronized (syncMonitor) {
      while (durablePosition < position) {
        if (syncFailure != null) {
          throw new IOException("WAL fsync failed", syncFailure);
        }
        if (isClosed()) {
          throw new IOException("WAL closed before write became durable");
        }
        try {
          syncMonitor.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted waiting for WAL fsync", e);
        }
      }
    }
  }

  void backgroundSync() {
    synchronized (syncMonitor) {
      try {
        forceLocked();
      } catch (IOException e) {
        LOG.log(System.Logger.Level.ERROR, "WAL background fsync failed", e);
        syncFailure = e;
        syncMonitor.notifyAll();
      }
    }
  }

  // Caller holds syncMonitor. Rotation also needs syncMonitor, so the channel stays open here.
  private void forceLocked() throws IOException {
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
    if (target > durablePosition) {
      ch.force(false);
      fsyncs.incrementAndGet();
      durablePosition = target;
      syncMonitor.notifyAll();
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
    synchronized (syncMonitor) {
      writeLock.lock();
      try {
        if (closed) {
          return;
        }
        if (policy.fsyncs()) {
          current.force(false);
          fsyncs.incrementAndGet();
        }
        durablePosition = appendedPosition;
        current.close();
        closed = true;
        syncMonitor.notifyAll();
      } finally {
        writeLock.unlock();
      }
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
