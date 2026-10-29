package dev.kaee.minikv.engine.sstable;

import dev.kaee.minikv.engine.Bytes;
import dev.kaee.minikv.engine.Entry;
import dev.kaee.minikv.engine.VersionedValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads an immutable SSTable. Opening loads the footer, index, bloom filter and key range into
 * memory; a point lookup then costs at most one block read. Safe for concurrent use.
 *
 * <p>Reference counted. The engine holds one reference while the table is live; each read holds
 * another for its duration. When compaction replaces the table, the engine deletes the file and
 * drops its reference; the channel closes when the last reader releases. On Linux and macOS an
 * unlinked file stays readable through an open channel, so in-flight reads finish normally.
 */
public final class SSTableReader implements AutoCloseable {

  private final Path path;
  private final FileChannel channel;
  private final long fileBytes;
  private final byte[][] lastKeys;
  private final long[] offsets;
  private final int[] lengths;
  private final int[] crcs;
  private final BloomFilter bloom;
  private final byte[] minKey;
  private final byte[] maxKey;
  private final long entryCount;
  private final long maxTimestamp;
  private final AtomicLong bloomNegatives = new AtomicLong();
  private final AtomicLong bloomFalsePositives = new AtomicLong();
  private final AtomicInteger refs = new AtomicInteger(1);

  private SSTableReader(Path path, FileChannel channel) throws IOException {
    this.path = path;
    this.channel = channel;
    this.fileBytes = channel.size();
    if (fileBytes < SSTableFormat.FOOTER_BYTES) {
      throw new CorruptSSTableException(path, "file shorter than footer");
    }
    byte[] fb = read(fileBytes - SSTableFormat.FOOTER_BYTES, SSTableFormat.FOOTER_BYTES);
    ByteBuffer footer = ByteBuffer.wrap(fb);
    int footerCrc = ByteBuffer.wrap(fb, fb.length - 4, 4).getInt();
    if (SSTableFormat.crc(fb, 0, fb.length - 4) != footerCrc) {
      throw new CorruptSSTableException(path, "footer crc mismatch");
    }
    if (footer.getLong() != SSTableFormat.MAGIC) {
      throw new CorruptSSTableException(path, "bad magic");
    }
    int version = footer.getInt();
    if (version != SSTableFormat.VERSION) {
      throw new CorruptSSTableException(path, "unsupported format version " + version);
    }
    ByteBuffer index = section(footer, "index");
    ByteBuffer bloomBlock = section(footer, "bloom");
    ByteBuffer meta = section(footer, "meta");
    this.entryCount = footer.getLong();
    this.maxTimestamp = footer.getLong();

    int blocks = index.getInt();
    if (blocks < 1 || blocks > index.remaining() / 20) {
      throw new CorruptSSTableException(path, "bad block count " + blocks);
    }
    lastKeys = new byte[blocks][];
    offsets = new long[blocks];
    lengths = new int[blocks];
    crcs = new int[blocks];
    for (int i = 0; i < blocks; i++) {
      lastKeys[i] = new byte[index.getInt()];
      index.get(lastKeys[i]);
      offsets[i] = index.getLong();
      lengths[i] = index.getInt();
      crcs[i] = index.getInt();
    }
    try {
      bloom = BloomFilter.deserialize(bloomBlock);
    } catch (IllegalArgumentException e) {
      throw new CorruptSSTableException(path, e.getMessage());
    }
    minKey = new byte[meta.getInt()];
    meta.get(minKey);
    maxKey = new byte[meta.getInt()];
    meta.get(maxKey);
  }

  public static SSTableReader open(Path path) throws IOException {
    FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
    try {
      return new SSTableReader(path, ch);
    } catch (IOException | RuntimeException e) {
      ch.close();
      if (e instanceof IOException io) {
        throw io;
      }
      throw new CorruptSSTableException(path, "malformed metadata: " + e);
    }
  }

  private ByteBuffer section(ByteBuffer footer, String name) throws IOException {
    long offset = footer.getLong();
    int length = footer.getInt();
    int crc = footer.getInt();
    if (offset < 0 || length < 0 || offset + length > fileBytes - SSTableFormat.FOOTER_BYTES) {
      throw new CorruptSSTableException(path, name + " block out of bounds");
    }
    byte[] data = read(offset, length);
    if (SSTableFormat.crc(data, 0, length) != crc) {
      throw new CorruptSSTableException(path, name + " block crc mismatch");
    }
    return ByteBuffer.wrap(data);
  }

  private byte[] read(long offset, int length) throws IOException {
    byte[] data = new byte[length];
    ByteBuffer buf = ByteBuffer.wrap(data);
    while (buf.hasRemaining()) {
      if (channel.read(buf, offset + buf.position()) < 0) {
        throw new CorruptSSTableException(path, "unexpected end of file");
      }
    }
    return data;
  }

  /** Newest version of {@code key} in this table, including tombstones. */
  public Optional<VersionedValue> get(byte[] key) throws IOException {
    if (Bytes.UNSIGNED.compare(key, minKey) < 0 || Bytes.UNSIGNED.compare(key, maxKey) > 0) {
      return Optional.empty();
    }
    if (!bloom.mightContain(key)) {
      bloomNegatives.incrementAndGet();
      return Optional.empty();
    }
    int block = findBlock(key);
    ByteBuffer data = readBlock(block);
    while (data.hasRemaining()) {
      Entry e = decode(data);
      int cmp = Bytes.UNSIGNED.compare(e.key(), key);
      if (cmp == 0) {
        return Optional.of(e.value());
      }
      if (cmp > 0) {
        break;
      }
    }
    bloomFalsePositives.incrementAndGet();
    return Optional.empty();
  }

  /** First block whose last key is {@code >= key}, or {@code lastKeys.length} if none. */
  private int findBlock(byte[] key) {
    int lo = 0;
    int hi = lastKeys.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (Bytes.UNSIGNED.compare(lastKeys[mid], key) < 0) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  private ByteBuffer readBlock(int i) throws IOException {
    byte[] data = read(offsets[i], lengths[i]);
    if (SSTableFormat.crc(data, 0, lengths[i]) != crcs[i]) {
      throw new CorruptSSTableException(path, "data block " + i + " crc mismatch");
    }
    return ByteBuffer.wrap(data);
  }

  private Entry decode(ByteBuffer b) throws CorruptSSTableException {
    try {
      byte[] key = new byte[b.getInt()];
      b.get(key);
      byte flags = b.get();
      long timestamp = b.getLong();
      byte[] value = new byte[b.getInt()];
      b.get(value);
      VersionedValue v =
          (flags & SSTableFormat.FLAG_TOMBSTONE) != 0
              ? VersionedValue.tombstoneAt(timestamp)
              : VersionedValue.of(value, timestamp);
      return new Entry(key, v);
    } catch (RuntimeException e) {
      throw new CorruptSSTableException(path, "malformed entry: " + e);
    }
  }

  /**
   * Entries in {@code [from, to)} in key order, tombstones included. A null bound means unbounded.
   * Blocks are read lazily. I/O errors surface as {@link UncheckedIOException}.
   */
  public Iterator<Entry> iterator(byte[] from, byte[] to) {
    return new Iterator<>() {
      private int block = from == null ? 0 : findBlock(from);
      private ByteBuffer data;
      private Entry next = advance();

      private Entry advance() {
        try {
          while (true) {
            if (data == null || !data.hasRemaining()) {
              if (block >= lastKeys.length) {
                return null;
              }
              data = readBlock(block++);
            }
            Entry e = decode(data);
            if (from != null && Bytes.UNSIGNED.compare(e.key(), from) < 0) {
              continue;
            }
            if (to != null && Bytes.UNSIGNED.compare(e.key(), to) >= 0) {
              block = lastKeys.length;
              data = null;
              return null;
            }
            return e;
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      @Override
      public boolean hasNext() {
        return next != null;
      }

      @Override
      public Entry next() {
        if (next == null) {
          throw new NoSuchElementException();
        }
        Entry current = next;
        next = advance();
        return current;
      }
    };
  }

  public Path path() {
    return path;
  }

  public long fileBytes() {
    return fileBytes;
  }

  public long entryCount() {
    return entryCount;
  }

  public long maxTimestamp() {
    return maxTimestamp;
  }

  public int blockCount() {
    return lastKeys.length;
  }

  public byte[] minKey() {
    return minKey.clone();
  }

  public byte[] maxKey() {
    return maxKey.clone();
  }

  /** Lookups the bloom filter answered without reading a block. */
  public long bloomNegatives() {
    return bloomNegatives.get();
  }

  /** Lookups that passed the bloom filter but found no key in the block. */
  public long bloomFalsePositives() {
    return bloomFalsePositives.get();
  }

  /** Takes a reference for a read. Returns false if the table is already fully released. */
  public boolean tryAcquire() {
    while (true) {
      int r = refs.get();
      if (r <= 0) {
        return false;
      }
      if (refs.compareAndSet(r, r + 1)) {
        return true;
      }
    }
  }

  /** Drops a reference; the last one closes the channel. */
  public void release() {
    if (refs.decrementAndGet() == 0) {
      try {
        channel.close();
      } catch (IOException e) {
        System.getLogger(SSTableReader.class.getName())
            .log(System.Logger.Level.WARNING, "closing " + path + " failed", e);
      }
    }
  }

  /** Deletes the file. Open readers keep working; call {@link #release()} afterwards. */
  public void deleteFile() throws IOException {
    Files.deleteIfExists(path);
  }

  /** Closes the channel regardless of outstanding references. Used when the engine shuts down. */
  @Override
  public void close() throws IOException {
    refs.set(0);
    channel.close();
  }
}
