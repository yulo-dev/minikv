# MiniKV benchmark results

Every number in this file is written by code from a benchmark run; none are edited by hand.
Regenerate with the command shown in each section.

<!-- engine:start -->
## Engine (JMH)

Command: `./gradlew benchReport`

- CPU: AMD EPYC 7763 64-Core Processor, 4 logical cores
- Memory: 15.6 GiB
- OS: Linux 7.0.0-1017-azure (amd64)
- JVM: OpenJDK 64-Bit Server VM 21.0.13+9-LTS
- Data directory: /tmp on ext4
- Commit: 86e6718
- JMH: 1 fork, 2 warmup and 3 measurement iterations of 3 s each, `-Xmx2g`
- Generated: 2026-10-29T07:55:13.792155053Z

Scores are JMH throughput means; ± is JMH's 99.9% confidence interval half-width. Data lives in `java.io.tmpdir`; see the machine details for its filesystem.

### `GetBenchmark.getHit`

| bloomBitsPerKey | valueSize | threads | score | ± | unit |
| --- | --- | ---: | ---: | ---: | --- |
| 1 | 100 | 1 | 373,953 | 79,646 | ops/s |
| 1 | 1024 | 1 | 362,495 | 39,428 | ops/s |
| 10 | 100 | 1 | 383,970 | 8,477 | ops/s |
| 10 | 1024 | 1 | 375,376 | 86,504 | ops/s |

### `GetBenchmark.getMiss`

| bloomBitsPerKey | valueSize | threads | score | ± | unit |
| --- | --- | ---: | ---: | ---: | --- |
| 1 | 100 | 1 | 595,937 | 82,862 | ops/s |
| 1 | 1024 | 1 | 587,889 | 52,766 | ops/s |
| 10 | 100 | 1 | 9,923,012 | 1,092,321 | ops/s |
| 10 | 1024 | 1 | 9,970,199 | 1,343,333 | ops/s |

### `PutBenchmark.put`

| fsync | order | valueSize | threads | score | ± | unit |
| --- | --- | --- | ---: | ---: | ---: | --- |
| NONE | sequential | 100 | 1 | 509,728 | 309,002 | ops/s |
| NONE | sequential | 1024 | 1 | 182,158 | 138,397 | ops/s |
| NONE | random | 100 | 1 | 399,398 | 117,800 | ops/s |
| NONE | random | 1024 | 1 | 179,347 | 131,588 | ops/s |
| BATCH | sequential | 100 | 1 | 199 | 3 | ops/s |
| BATCH | sequential | 1024 | 1 | 196 | 101 | ops/s |
| BATCH | random | 100 | 1 | 200 | 11 | ops/s |
| BATCH | random | 1024 | 1 | 199 | 20 | ops/s |
| ALWAYS | sequential | 100 | 1 | 4,657 | 1,188 | ops/s |
| ALWAYS | sequential | 1024 | 1 | 4,498 | 660 | ops/s |
| ALWAYS | random | 100 | 1 | 4,741 | 6,280 | ops/s |
| ALWAYS | random | 1024 | 1 | 4,490 | 1,530 | ops/s |

### `PutBenchmark.put16Threads`

| fsync | order | valueSize | threads | score | ± | unit |
| --- | --- | --- | ---: | ---: | ---: | --- |
| NONE | sequential | 100 | 16 | 254,604 | 81,085 | ops/s |
| NONE | sequential | 1024 | 16 | 204,225 | 374,845 | ops/s |
| NONE | random | 100 | 16 | 199,015 | 42,069 | ops/s |
| NONE | random | 1024 | 16 | 163,572 | 20,205 | ops/s |
| BATCH | sequential | 100 | 16 | 3,188 | 135 | ops/s |
| BATCH | sequential | 1024 | 16 | 3,195 | 97 | ops/s |
| BATCH | random | 100 | 16 | 3,168 | 82 | ops/s |
| BATCH | random | 1024 | 16 | 3,190 | 20 | ops/s |
| ALWAYS | sequential | 100 | 16 | 18,343 | 62,350 | ops/s |
| ALWAYS | sequential | 1024 | 16 | 20,680 | 83,525 | ops/s |
| ALWAYS | random | 100 | 16 | 23,088 | 33,110 | ops/s |
| ALWAYS | random | 1024 | 16 | 22,418 | 39,008 | ops/s |

### `ScanBenchmark.scan1000`

| valueSize | threads | score | ± | unit |
| --- | ---: | ---: | ---: | --- |
| 100 | 1 | 8,193 | 4,374 | ops/s |
| 1024 | 1 | 1,666 | 214 | ops/s |

<!-- engine:end -->
