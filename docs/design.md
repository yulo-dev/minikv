# MiniKV engine design

This document covers the single-node storage engine in `engine/`. The replicated cluster layer is planned, not implemented. Measured numbers referenced here come from `docs/results.md` (commit `534c675`, GitHub Actions runner, AMD EPYC 7763 with 4 logical cores).

## Data model

- Keys are 1 to 1,024 bytes and sort in unsigned lexicographic order everywhere (`Bytes.UNSIGNED`). Values are 0 to 1 MiB.
- Every write carries a 64-bit timestamp chosen by the caller. For a given key, the highest timestamp wins (last-write-wins), wherever and whenever that version arrived. Equal timestamps are treated as the same write.
- `Timestamps` defines the layout a hybrid logical clock will produce: `physicalMillis:48 | logical:8 | nodeId:8`. Comparing timestamps as plain longs therefore orders them by physical time, then the logical counter, then node id. The engine itself uses only the physical part, to age tombstones.
- A delete writes a tombstone. `get` returns tombstones so that callers, such as a future replica coordinator, can compare versions. `scan` hides them.

## Write path and group commit

A write appends one record to the active WAL segment, waits until that record is durable under the fsync mode, then inserts it into the active memtable. The write holds the engine's rotation read lock through all three steps. This guarantees that a record in WAL segment `k` is always in the memtable that covers segment `k`, which is what makes deleting segment `k` safe once that memtable has been flushed.

Fsync modes (`SyncPolicy`, a Strategy):

| Mode | Acknowledges after | Notes |
| --- | --- | --- |
| `ALWAYS` (default) | an fsync that covers the record | leader/follower group commit |
| `BATCH` | the next background fsync, started every interval (default 5 ms) | one writer is capped near one write per interval |
| `NONE` | the `write()` call | survives a process crash, not a power failure; tests and benchmarks only |

**Leader/follower group commit.** A writer whose position is already durable returns at once, without taking a lock. Otherwise it takes `syncLock`. If no fsync is running, it becomes the leader: it snapshots the appended position, marks an fsync as in progress, releases `syncLock`, calls `force`, then re-locks, publishes the new durable position and signals everyone. Writers that arrive while the fsync runs wait on the condition instead of queueing for the lock. When the fsync ends they all wake together; those it covered return, and one of the rest leads the next fsync on behalf of all of them. Rotation and close wait until no fsync is in progress before they touch the channel. A failed fsync leaves the file's durable state unknown, so it poisons the log and every later durability wait fails.

**Measured finding: timer-based BATCH is slower than ALWAYS.** The spec's original default was BATCH every 5 ms. Measured with sequential keys and 100 B values:

| | 1 writer | 16 writers |
| --- | ---: | ---: |
| BATCH | 200 ± 0 ops/s | 3,193 ± 10 ops/s |
| ALWAYS | 5,146 ± 38 ops/s | 26,382 ± 677 ops/s |

A BATCH writer waits for the next timer tick even when the disk is idle, so its throughput is about writers × (1 / interval). On a device where an fsync takes much less than the interval, ALWAYS with group commit gives both lower latency and higher throughput, and it never acknowledges a write that is not yet durable. The default therefore changed to ALWAYS. BATCH would only win where fsync is far slower than the interval and writers far outnumber what one fsync can cover; that case was not measured here.

**Two bugs found by benchmarking, both fixed:**
1. BATCH originally used a fixed *delay*, so the real period was 5 ms plus the fsync time. It now runs at a fixed *rate*.
2. The first group-commit version held a monitor during `force`. Writers queued for the monitor instead of the result, and whichever thread acquired it next fsynced for its own record. It averaged about 1.2 records per fsync, and 16 writers were no faster than one. The leader/follower design replaced it, and `GroupCommitTest` now requires at least 2 records per fsync.

## On-disk formats

All integers are big-endian, and every checksum is CRC32C.

**WAL record:** `length:int | crc:int | type:byte | timestamp:long | keyLen:int | key | valueLen:int | value`. `length` and `crc` cover the bytes after the crc. Segments are named `wal-<20-digit id>.log`, and ids only increase.

**SSTable** (`sst-<20-digit id>.sst`):
```
data blocks    ~4 KiB each: keyLen:int | key | flags:byte | timestamp:long | valueLen:int | value
index block    count:int, then per block: lastKey, offset:long, length:int, crc:int
bloom block    numHashes:int | numLongs:int | bits:long[]
meta block     minKey, maxKey
footer (80 B)  magic | version | (offset, length, crc) for index, bloom, meta
               | entryCount | maxTimestamp | footerCrc
```
A table is written to `<name>.tmp`, fsynced, renamed, and then its directory is fsynced. A reader loads the footer, index, bloom filter and key range into memory, so a point lookup reads at most one data block.

**Bloom filter:** probes use Kirsch-Mitzenmacher double hashing, `h1 + i * h2`, where `h1` and `h2` are the halves of a Murmur3 x64-128 hash. Both the filter and the hash are written from scratch, and the hash is checked against reference vectors. The probe count is `round(bitsPerKey * ln 2)`. `BloomFilterTest` measures the false-positive rate on one million absent keys and requires it to stay within 2× of theory.

**Manifest:** an append-only file of edits, each framed `length | crc | body`. An edit records added table ids, removed table ids, and the highest WAL segment whose data is now in tables.

## Crash safety

The engine follows one rule: write and fsync the record that makes a step safe, then act on it.

| Step | Durable first | Then |
| --- | --- | --- |
| Flush | SSTable file and directory entry; then a manifest edit adding the table and retiring WAL segments up to `N` | swap the table into the read path, delete WAL segments up to `N` |
| Compaction | output SSTable; then a manifest edit adding the output and removing the inputs | swap, unlink the inputs |

**Recovery:**
1. Replay the manifest.
2. Delete `.tmp` files, any table the manifest does not list, and any WAL segment it has retired.
3. Open the live tables.
4. Replay the remaining WAL segments into a memtable.
5. Start a new segment.

A bad record in the *last* WAL segment, or at the end of the manifest, is a torn write from a crash in the middle of an append. Those bytes were never acknowledged, so recovery truncates them. A bad record anywhere earlier cannot be a torn write, because segments are fsynced before rotation, so recovery reports it as corruption instead of silently dropping acknowledged data.

## Read path

`get` takes a snapshot of the read path: active memtable, frozen memtables, and SSTables. It holds a reference on every table in the snapshot and checks each component. Because writes may arrive out of timestamp order, a newer component can hold an older version, so the engine never stops at the first hit. It skips any SSTable whose `maxTimestamp` is no greater than the best version found so far, and in each remaining table it checks the key range and the bloom filter before reading a block.

`scan` feeds every component into a k-way `MergeIterator`: a heap of source heads that emits one entry per key, the one with the highest timestamp. It filters out tombstones. Table references are released when the iterator is exhausted.

## Compaction and tombstones

`SizeTieredStrategy` (a Strategy) puts tables into buckets of similar size, within 0.5× to 1.5× of the bucket average. Every table smaller than one memtable goes into a shared bucket. When a bucket has at least 4 tables, the strategy merges up to 32 of them, choosing the bucket with the smallest average first. Compaction runs on its own thread. It blocks writers only during the brief state swap, under the rotation write lock, and never blocks readers. A replaced table's file is deleted right away; readers that still hold a reference keep reading the unlinked file, and its channel closes when the last reference is released.

A tombstone is dropped during compaction only when both of these hold:
1. Its physical time is older than `gc_grace` (default 10 minutes). This leaves time for replicas that missed the delete to receive it.
2. No memtable, and no table outside this compaction, contains the key.

The spec's rule was "the compaction includes the oldest table". That assumes older tables hold older timestamps, which is false here: a newer table can hold an older value that arrived late, and dropping the tombstone would bring that value back. `CompactionTest` covers that case, and the case where the older value sits in the memtable.

## Concurrency summary

| Lock or mechanism | Protects |
| --- | --- |
| Rotation `ReentrantReadWriteLock` | Writers take it shared. Memtable rotation and the flush and compaction swaps take it exclusive. |
| WAL `writeLock` | Appending bytes and the appended position |
| WAL `syncLock` with a condition | Durable position, fsync in progress, fsync failure. Lock order is `syncLock` before `writeLock`. |
| Volatile `State` record | Immutable snapshot of the read path, replaced on every swap |
| `SSTableReader` reference count | When a replaced table's channel closes |
| `flushMonitor` | Back-pressure: writers wait while 4 frozen memtables are queued for flush |

## Design patterns

| Pattern | Where |
| --- | --- |
| Iterator | `MergeIterator`, `SSTableReader.iterator`, the streaming scan iterator |
| Strategy | `SyncPolicy` (fsync mode), `CompactionStrategy` / `SizeTieredStrategy`, `TombstonePolicy` |
| Builder | `EngineConfig.Builder`, `SSTableWriter.Builder` |
| Factory | `Engines.open` (runs recovery and wires the components), `SyncPolicy.create` |
| Observer | Planned with the metrics layer; not implemented |

## Testing approach

- **Model-based:** jqwik generates sequences of put, delete, get, scan, flush, compact and restart, and compares each against a `TreeMap`. A tiny memtable and block size make short sequences rotate, flush, compact and build multi-block tables.
- **Crash:** a child JVM writes with ALWAYS and prints each acknowledged op. The parent SIGKILLs it at a random time, 60 times, then reopens the data and checks every acknowledged op against a replay of the same seeded workload. The kill goes through `ProcessHandle`, because `Process.destroyForcibly` also closes the parent's end of the pipe and would drop acknowledgements still in it.
- **Mutation checks during development:** deleting WAL segments before the manifest edit, skipping the tombstone safety check, and breaking cross-table last-write-wins were each planted on purpose and caught.
