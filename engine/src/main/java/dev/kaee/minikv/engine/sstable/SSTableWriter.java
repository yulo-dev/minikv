package dev.kaee.minikv.engine.sstable;

import dev.kaee.minikv.engine.Bytes;
import dev.kaee.minikv.engine.VersionedValue;
import dev.kaee.minikv.engine.wal.FileSync;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writes one SSTable from entries added in strictly increasing key order.
 *
 * <p>Data goes to {@code <target>.tmp}. {@link #finish()} fsyncs it, renames it to the target and
 * fsyncs the directory, so the target path either does not exist or holds a complete table.
 */
public final class SSTableWriter implements AutoCloseable {

  public static final int DEFAULT_BLOCK_BYTES = 4096;
  public static final int DEFAULT_BITS_PER_KEY = 10;

  private final Path target;
  private final Path temp;
  private final FileChannel channel;
  private final int blockBytes;
  private final BloomFilter.Builder bloom;

  private final ByteArrayOutputStream block = new ByteArrayOutputStream();
  private final DataOutputStream blockOut = new DataOutputStream(block);
  private final ByteArrayOutputStream index = new ByteArrayOutputStream();
  private final DataOutputStream indexOut = new DataOutputStream(index);

  private long position;
  private int blockCount;
  private long entryCount;
  private long maxTimestamp = Long.MIN_VALUE;
  private byte[] firstKey;
  private byte[] lastKey;
  private boolean finished;

  private SSTableWriter(Path target, int blockBytes, int bitsPerKey) throws IOException {
    this.target = target;
    this.temp = target.resolveSibling(target.getFileName() + ".tmp");
    this.blockBytes = blockBytes;
    this.bloom = new BloomFilter.Builder(bitsPerKey);
    this.channel =
        FileChannel.open(
            temp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
  }

  public static Builder builder(Path target) {
    return new Builder(target);
  }

  public void add(byte[] key, VersionedValue v) throws IOException {
    if (finished) {
      throw new IllegalStateException("writer already finished");
    }
    if (lastKey != null && Bytes.UNSIGNED.compare(lastKey, key) >= 0) {
      throw new IllegalArgumentException("keys must be added in strictly increasing order");
    }
    blockOut.writeInt(key.length);
    blockOut.write(key);
    blockOut.writeByte(v.tombstone() ? SSTableFormat.FLAG_TOMBSTONE : 0);
    blockOut.writeLong(v.timestamp());
    blockOut.writeInt(v.value().length);
    blockOut.write(v.value());

    bloom.add(key);
    if (firstKey == null) {
      firstKey = key;
    }
    lastKey = key;
    entryCount++;
    maxTimestamp = Math.max(maxTimestamp, v.timestamp());
    if (block.size() >= blockBytes) {
      finishBlock();
    }
  }

  private void finishBlock() throws IOException {
    if (block.size() == 0) {
      return;
    }
    byte[] data = block.toByteArray();
    indexOut.writeInt(lastKey.length);
    indexOut.write(lastKey);
    indexOut.writeLong(position);
    indexOut.writeInt(data.length);
    indexOut.writeInt(SSTableFormat.crc(data, 0, data.length));
    writeFully(data);
    blockCount++;
    block.reset();
  }

  /** Completes the table and atomically publishes it at the target path. */
  public Path finish() throws IOException {
    if (entryCount == 0) {
      throw new IllegalStateException("an SSTable needs at least one entry");
    }
    finishBlock();

    byte[] indexBody = index.toByteArray();
    byte[] indexBlock =
        ByteBuffer.allocate(4 + indexBody.length).putInt(blockCount).put(indexBody).array();
    byte[] bloomBlock = bloom.build().serialize();
    byte[] metaBlock =
        ByteBuffer.allocate(8 + firstKey.length + lastKey.length)
            .putInt(firstKey.length)
            .put(firstKey)
            .putInt(lastKey.length)
            .put(lastKey)
            .array();

    ByteBuffer footer = ByteBuffer.allocate(SSTableFormat.FOOTER_BYTES);
    footer.putLong(SSTableFormat.MAGIC).putInt(SSTableFormat.VERSION);
    for (byte[] section : new byte[][] {indexBlock, bloomBlock, metaBlock}) {
      footer.putLong(position).putInt(section.length);
      footer.putInt(SSTableFormat.crc(section, 0, section.length));
      writeFully(section);
    }
    footer.putLong(entryCount).putLong(maxTimestamp);
    footer.putInt(SSTableFormat.crc(footer.array(), 0, footer.position()));
    writeFully(footer.array());

    channel.force(true);
    channel.close();
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    FileSync.fsyncDirectory(target.getParent());
    finished = true;
    return target;
  }

  private void writeFully(byte[] data) throws IOException {
    ByteBuffer buf = ByteBuffer.wrap(data);
    while (buf.hasRemaining()) {
      channel.write(buf);
    }
    position += data.length;
  }

  /** Abandons an unfinished table and deletes its temp file. */
  @Override
  public void close() throws IOException {
    if (!finished) {
      channel.close();
      Files.deleteIfExists(temp);
    }
  }

  /** Builder for writer settings. */
  public static final class Builder {
    private final Path target;
    private int blockBytes = DEFAULT_BLOCK_BYTES;
    private int bitsPerKey = DEFAULT_BITS_PER_KEY;

    private Builder(Path target) {
      this.target = target;
    }

    public Builder blockBytes(int bytes) {
      if (bytes < 64) {
        throw new IllegalArgumentException("blockBytes must be at least 64");
      }
      this.blockBytes = bytes;
      return this;
    }

    public Builder bitsPerKey(int bits) {
      this.bitsPerKey = bits;
      return this;
    }

    public SSTableWriter open() throws IOException {
      return new SSTableWriter(target, blockBytes, bitsPerKey);
    }
  }
}
