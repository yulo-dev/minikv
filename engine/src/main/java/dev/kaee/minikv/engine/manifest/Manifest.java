package dev.kaee.minikv.engine.manifest;

import dev.kaee.minikv.engine.wal.FileSync;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.CRC32C;

/**
 * Append-only log of engine state changes, replayed on startup.
 *
 * <p>Each record is one {@link Edit}, framed as {@code length:int | crc32c:int | body}. An edit is
 * written and fsynced before the engine acts on it, so after a crash the manifest never names a
 * table that is missing and never forgets a table whose WAL segments were deleted. A torn last
 * record was never acted on and is dropped; a bad record before the end is corruption.
 *
 * <p>The file grows by one small record per flush or compaction and is not rewritten yet.
 */
public final class Manifest implements AutoCloseable {

  public static final String FILE_NAME = "MANIFEST";
  private static final System.Logger LOG = System.getLogger(Manifest.class.getName());
  private static final int MAX_BODY_BYTES = 1 << 20;

  /** Live tables, ascending by id, and the highest WAL segment whose data is in tables. */
  public record State(List<Long> liveTables, long walRetiredThrough, long maxFileId) {}

  private final FileChannel channel;
  private final TreeSet<Long> live = new TreeSet<>();
  private long walRetiredThrough;
  private long maxFileId;

  private Manifest(Path file) throws IOException {
    replay(file);
    channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    channel.position(channel.size());
  }

  public static Manifest open(Path dataDir) throws IOException {
    Files.createDirectories(dataDir);
    Path file = dataDir.resolve(FILE_NAME);
    boolean created = !Files.exists(file);
    Manifest m = new Manifest(file);
    if (created) {
      FileSync.fsyncDirectory(dataDir);
    }
    return m;
  }

  public synchronized State state() {
    return new State(
        Collections.unmodifiableList(new ArrayList<>(live)), walRetiredThrough, maxFileId);
  }

  /** Reserves an id for a new SSTable. Ids only grow, so a larger id means a newer table. */
  public synchronized long nextFileId() {
    return ++maxFileId;
  }

  /** Durably records the edit, then applies it to the in-memory state. */
  public synchronized void apply(Edit edit) throws IOException {
    byte[] body = edit.encode();
    ByteBuffer frame = ByteBuffer.allocate(8 + body.length);
    frame.putInt(body.length).putInt(crc(body)).put(body).flip();
    while (frame.hasRemaining()) {
      channel.write(frame);
    }
    channel.force(false);
    applyInMemory(edit);
  }

  private void applyInMemory(Edit edit) {
    live.addAll(edit.added());
    edit.removed().forEach(live::remove);
    walRetiredThrough = Math.max(walRetiredThrough, edit.walRetiredThrough());
    for (long id : edit.added()) {
      maxFileId = Math.max(maxFileId, id);
    }
  }

  private void replay(Path file) throws IOException {
    if (!Files.exists(file)) {
      return;
    }
    long size = Files.size(file);
    long offset = 0;
    String problem = null;
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      while (offset < size) {
        byte[] header = in.readNBytes(8);
        if (header.length < 8) {
          problem = "truncated header";
          break;
        }
        ByteBuffer h = ByteBuffer.wrap(header);
        int length = h.getInt();
        int expected = h.getInt();
        if (length < 0 || length > MAX_BODY_BYTES) {
          problem = "bad length " + length;
          break;
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
          problem = "truncated body";
          break;
        }
        if (crc(body) != expected) {
          problem = "crc mismatch";
          break;
        }
        Edit edit;
        try {
          edit = Edit.decode(body);
        } catch (RuntimeException e) {
          problem = "malformed edit";
          break;
        }
        applyInMemory(edit);
        offset += 8 + length;
      }
    }
    if (problem == null) {
      return;
    }
    if (!isLastRecord(file, offset)) {
      throw new IOException("corrupt manifest " + file + " at offset " + offset + ": " + problem);
    }
    LOG.log(
        System.Logger.Level.WARNING,
        "torn manifest tail at offset {0} ({1}); truncating",
        offset,
        problem);
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
      ch.truncate(offset);
      ch.force(true);
    }
  }

  // A torn write can only damage the final record. If a complete, valid-looking frame could still
  // follow the bad one, the damage is in the middle of the log.
  private static boolean isLastRecord(Path file, long offset) throws IOException {
    long size = Files.size(file);
    if (size - offset < 8) {
      return true;
    }
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
      ByteBuffer h = ByteBuffer.allocate(4);
      ch.read(h, offset);
      int length = h.flip().getInt();
      return length < 0 || length > MAX_BODY_BYTES || offset + 8 + length >= size;
    }
  }

  private static int crc(byte[] body) {
    CRC32C c = new CRC32C();
    c.update(body, 0, body.length);
    return (int) c.getValue();
  }

  @Override
  public synchronized void close() throws IOException {
    channel.close();
  }
}
