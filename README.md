# MiniKV

MiniKV is a log-structured merge-tree (LSM) key-value storage engine written from scratch in Java 21, with no runtime dependencies. It has a write-ahead log with leader/follower group commit, skip-list memtables, SSTables with block indexes and bloom filters, a manifest for crash-safe flush and compaction, and size-tiered compaction with tombstone garbage collection. Model-based property tests and repeated SIGKILL crash tests check its correctness; a replicated cluster layer is planned but not built.

## Results

All numbers below come from [`docs/results.md`](docs/results.md), which `./gradlew benchReport` generates from JMH output. That run used a GitHub Actions runner: AMD EPYC 7763 (4 logical cores), 15.6 GiB RAM, Linux 7.0.0-1017-azure, ext4, OpenJDK 21.0.13, commit `534c675`. JMH settings were 3 forks, 2 warmup and 5 measurement iterations of 3 s each. Scores are ops/s, and ± is the 99.9% confidence interval half-width.

**Writes** (sequential keys, 100 B values, through WAL, memtable, flush and compaction):

| fsync mode | 1 writer | 16 writers |
| --- | ---: | ---: |
| NONE (no fsync) | 502,424 ± 14,266 | 251,554 ± 11,224 |
| BATCH (fsync every 5 ms) | 200 ± 0 | 3,193 ± 10 |
| ALWAYS (default, group commit) | 5,146 ± 38 | 26,382 ± 677 |

**Reads** (one compacted SSTable of 200,000 keys, 100 B values, one thread):

| operation | bloom bits/key | ops/s |
| --- | ---: | ---: |
| get, key present | 10 | 360,461 ± 7,368 |
| get, key absent | 10 | 9,910,479 ± 66,822 |
| get, key absent | 1 | 567,169 ± 7,048 |
| scan 1,000 keys | 10 | 8,541 ± 191 |

What the tables show:

- **Group commit works.** With ALWAYS, 16 writers share fsyncs and reach 26,382 ops/s against 5,146 for one writer.
- **Timer-based BATCH lost to ALWAYS in every configuration measured.** A BATCH writer waits for the next 5 ms tick, so throughput equals writers × 200/s (200 and 3,193). ALWAYS fsyncs as soon as a leader is free and acknowledges only durable writes, so it became the default. BATCH is still available.
- **Bloom filters skip block reads for absent keys.** At 10 bits per key, misses run at 9.9M ops/s; at 1 bit per key, most misses read a block and drop to 0.57M ops/s.
- **Unsynced writes do not scale on this 4-core runner.** NONE with 16 writers is slower than with one, because writers contend on the WAL append lock once fsync no longer dominates.

The full tables, covering 1 KiB values, random keys and every bloom setting, are in [`docs/results.md`](docs/results.md). Numbers from different runners are not comparable.

## Architecture

```
 WRITE PATH                                   READ PATH: newest version by timestamp wins
                                              get(k) / scan(from, to)
 put(k, v, ts) / delete(k, ts)                  1. active memtable
   |                                            2. frozen memtables
   | 1. append to WAL segment N; wait until     3. SSTables: key range -> bloom filter
   |    durable (leader/follower group commit)     -> block index -> one 4 KiB block;
   v                                               skip a table whose max timestamp
 active memtable (ConcurrentSkipListMap)           cannot beat the best version so far
   | 2. at 4 MiB: freeze, start WAL segment N+1    scan: k-way merge of all sources
   v
 frozen memtable --(flush thread)--> SSTable: write .tmp, fsync, rename, fsync dir
   | 3. MANIFEST += {add table, WAL retired through N}; fsync
   | 4. swap table into the read path; delete WAL segments <= N
   v
 SSTables --(compaction thread, size-tiered: 4 to 32 tables of similar size)
   |   k-way merge, newest version per key, drop expired tombstones when safe
   | 5. MANIFEST += {add output, remove inputs}; fsync
   | 6. swap; unlink inputs (in-flight reads keep their reference-counted handles)
   v
 RECOVERY: replay MANIFEST -> delete .tmp files, unlisted tables and retired WAL
 segments -> open tables -> replay WAL (truncate a torn tail in the last segment;
 damage in an earlier segment is a hard error) -> start a new segment
```

Every step that deletes data runs only after a fsynced manifest record makes the deletion safe, so a crash between any two steps leaves either the old state or the new one. Design details, file formats and the named design patterns are in [`docs/design.md`](docs/design.md).

## Design decisions

| Problem | Choice | Trade-off |
| --- | --- | --- |
| Durability vs write latency | ALWAYS by default: every write waits for an fsync, and concurrent writers share it through leader/follower group commit | One writer pays a full fsync per put; BATCH (timed) and NONE (tests only) remain configurable |
| Compaction | Size-tiered | Less write amplification than leveled; more space amplification and more tables per read, offset by bloom filters and max-timestamp pruning |
| Conflicting versions | Last-write-wins on caller timestamps (`physicalMillis:48 \| logical:8 \| nodeId:8`), not arrival order | Simple and order-independent; concurrent writes to one key keep only the newest, with no siblings |
| Deleting data | Tombstones dropped only past `gc_grace` (10 min) and only when no memtable or table outside the compaction holds the key | Stricter than "the compaction includes the oldest table", because out-of-order timestamps let a newer table hold an older value; tombstones live longer |
| Crash safety of flush and compaction | Append-only manifest, fsynced before acting; orphans removed on open | One extra fsync per flush or compaction; the manifest is never rewritten |
| Reads during compaction | Reference-counted SSTable readers; replaced files are unlinked immediately | Relies on POSIX unlink semantics; not supported on Windows |

## Consistency model

This is a single-node engine, so the R + W > N quorum guarantees in the original spec belong to the planned cluster layer and do not apply yet.

- For each key, `get` returns the version with the highest timestamp among applied writes, including a tombstone if that version is a delete. Arrival order does not matter, and equal timestamps are treated as the same write.
- `scan(from, to)` returns live entries in `[from, to)` in unsigned byte order. It reads a snapshot of tables, but the active memtable is read live, so writes made during a scan may or may not appear.
- Durability depends on the fsync mode. With ALWAYS (the default) or BATCH, a write that has returned is in an fsynced WAL segment. With NONE, it survives a process crash but not a power failure.
- On macOS, `FileChannel.force` issues `F_FULLFSYNC` (JDK-8080589). On Linux it issues `fdatasync`.

## Correctness guarantees and the tests behind them

| Guarantee | Test |
| --- | --- |
| Every acknowledged write survives SIGKILL, including kills during flush and compaction | `CrashRecoveryTest`: 60 kills at random times per CI run, every acknowledged op checked against a seeded replay |
| A torn WAL tail loses only records past the tear | `RecoveryTest.tornTailAtRandomOffsetsLosesOnlyRecordsPastTheCut`: 100 cut points |
| Damage before the WAL tail or in the manifest is reported, not skipped | `WalReplayTest`, `ManifestTest` |
| Under random put, delete, get, scan, flush, compact and restart, the engine behaves like a sorted map | `EngineModelTest` (jqwik): 1,000 sequences per CI run |
| The newest timestamp wins across memtables and tables | `FlushTest`, `EngineTest`, `MergeIteratorTest` |
| A tombstone is never dropped while an older value could resurface | `CompactionTest.tombstoneShadowingAnOlderValueIn{AnotherTable,TheMemtable}Survives` |
| Reads stay correct while flush and compaction run | `CompactionTest.readsStayCorrectWhileCompactionRuns` |
| Corrupt SSTables are detected, never returned | `SSTableTest.corruptionIsDetectedNotReturned` |
| Crash leftovers are cleaned up on open | `FlushTest.openRemovesWhatACrashLeavesBehind` |
| Bloom filters have no false negatives, and their false-positive rate is within 2× of theory | `BloomFilterTest` |
| Concurrent writers share fsyncs (at least 2 records per fsync) | `GroupCommitTest` |

To check that the tests can fail, specific bugs were planted during development: unsafe flush ordering, broken tombstone checks, and lost cross-table last-write-wins. Each was caught.

## Run locally

Requires JDK 21 or newer. Gradle is downloaded by the wrapper.

```bash
./gradlew build                  # format check, Error Prone, unit and property tests
./gradlew :engine:crashTest      # SIGKILL crash and torn-WAL tests (about 1 minute)
./gradlew benchReport            # JMH benchmarks, rewrites docs/results.md (about 40 minutes)
./gradlew benchReport -Pquick    # smoke run of the benchmark pipeline, writes build/results-quick.md
```

Using the engine from Java:

```java
EngineConfig config = EngineConfig.builder(Path.of("data")).build(); // ALWAYS fsync by default
try (Engine engine = Engines.open(config)) {                          // runs crash recovery
  long ts = Timestamps.of(System.currentTimeMillis(), 0, 0);
  engine.put(key, value, ts);
  Optional<VersionedValue> latest = engine.get(key);                   // includes tombstones
  engine.delete(key, ts + 1);
  engine.scan(from, to).forEachRemaining(entry -> { /* live entries in [from, to) */ });
}
```

## Project structure

```
engine/       storage engine library, no runtime dependencies
  wal/        segments, record codec, fsync policies, leader/follower group commit, replay
  memtable/   skip-list memtable
  sstable/    writer, reader, block index, bloom filter
  manifest/   append-only log of table and WAL-retirement edits
  compaction/ k-way merge iterator, size-tiered strategy
  util/       Murmur3 x64-128
bench/        JMH benchmarks and the docs/results.md generator
docs/         design.md, results.md (generated)
cluster/ proto/ client/ it/   empty placeholders for the planned cluster layer
.github/      CI: build, unit, crash; on-demand benchmark workflow
```

## Limitations

- **The cluster layer is planned, not implemented.** That includes partitioning, replication, quorums, hinted handoff, read repair, failure detection, gRPC, the CLI and metrics. The `cluster`, `proto`, `client` and `it` modules are empty.
- Callers supply timestamps. The layout is defined in `Timestamps`, but there is no hybrid logical clock yet, and tombstone aging assumes callers follow that layout.
- Last-write-wins keeps only the newest of concurrent writes to one key.
- The SIGKILL test proves crash ordering, not durability under power loss, because the OS page cache survives a killed process.
- The manifest is never compacted and grows by one small record per flush or compaction.
- There is no prefix compression and no block compression.
- A scan iterator abandoned before it is exhausted keeps its tables' files open until the engine closes.
- Benchmarks ran on a single shared CI VM. Absolute numbers vary between runners, and unsynced writes contend on the WAL append lock with many writers.
