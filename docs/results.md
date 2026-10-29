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
- Commit: 534c675
- JMH: 3 forks, 2 warmup and 5 measurement iterations of 3 s each, `-Xmx2g`
- Generated: 2026-10-29T17:01:33.851528265Z

Scores are JMH throughput means; ± is JMH's 99.9% confidence interval half-width. Data lives in `java.io.tmpdir`; see the machine details for its filesystem.

### `GetBenchmark.getHit`

| bloomBitsPerKey | valueSize | threads | score | ± | unit |
| --- | --- | ---: | ---: | ---: | --- |
| 1 | 100 | 1 | 367,604 | 6,727 | ops/s |
| 1 | 1024 | 1 | 365,957 | 8,755 | ops/s |
| 10 | 100 | 1 | 360,461 | 7,368 | ops/s |
| 10 | 1024 | 1 | 357,553 | 9,937 | ops/s |

### `GetBenchmark.getMiss`

| bloomBitsPerKey | valueSize | threads | score | ± | unit |
| --- | --- | ---: | ---: | ---: | --- |
| 1 | 100 | 1 | 567,169 | 7,048 | ops/s |
| 1 | 1024 | 1 | 559,911 | 18,082 | ops/s |
| 10 | 100 | 1 | 9,910,479 | 66,822 | ops/s |
| 10 | 1024 | 1 | 9,921,988 | 146,230 | ops/s |

### `PutBenchmark.put`

| fsync | order | valueSize | threads | score | ± | unit |
| --- | --- | --- | ---: | ---: | ---: | --- |
| NONE | sequential | 100 | 1 | 502,424 | 14,266 | ops/s |
| NONE | sequential | 1024 | 1 | 181,285 | 13,293 | ops/s |
| NONE | random | 100 | 1 | 388,760 | 7,501 | ops/s |
| NONE | random | 1024 | 1 | 177,275 | 11,718 | ops/s |
| BATCH | sequential | 100 | 1 | 200 | 0 | ops/s |
| BATCH | sequential | 1024 | 1 | 200 | 0 | ops/s |
| BATCH | random | 100 | 1 | 200 | 0 | ops/s |
| BATCH | random | 1024 | 1 | 200 | 0 | ops/s |
| ALWAYS | sequential | 100 | 1 | 5,146 | 38 | ops/s |
| ALWAYS | sequential | 1024 | 1 | 4,696 | 45 | ops/s |
| ALWAYS | random | 100 | 1 | 5,104 | 48 | ops/s |
| ALWAYS | random | 1024 | 1 | 4,719 | 60 | ops/s |

### `PutBenchmark.put16Threads`

| fsync | order | valueSize | threads | score | ± | unit |
| --- | --- | --- | ---: | ---: | ---: | --- |
| NONE | sequential | 100 | 16 | 251,554 | 11,224 | ops/s |
| NONE | sequential | 1024 | 16 | 189,557 | 15,874 | ops/s |
| NONE | random | 100 | 16 | 197,713 | 3,915 | ops/s |
| NONE | random | 1024 | 16 | 155,853 | 8,121 | ops/s |
| BATCH | sequential | 100 | 16 | 3,193 | 10 | ops/s |
| BATCH | sequential | 1024 | 16 | 3,198 | 3 | ops/s |
| BATCH | random | 100 | 16 | 3,196 | 10 | ops/s |
| BATCH | random | 1024 | 16 | 3,199 | 2 | ops/s |
| ALWAYS | sequential | 100 | 16 | 26,382 | 677 | ops/s |
| ALWAYS | sequential | 1024 | 16 | 25,035 | 3,125 | ops/s |
| ALWAYS | random | 100 | 16 | 25,565 | 405 | ops/s |
| ALWAYS | random | 1024 | 16 | 24,402 | 2,553 | ops/s |

### `ScanBenchmark.scan1000`

| valueSize | threads | score | ± | unit |
| --- | ---: | ---: | ---: | --- |
| 100 | 1 | 8,541 | 191 | ops/s |
| 1024 | 1 | 1,721 | 65 | ops/s |

<!-- engine:end -->
