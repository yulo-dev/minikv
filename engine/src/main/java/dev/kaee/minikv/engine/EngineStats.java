package dev.kaee.minikv.engine;

/** Point-in-time engine counters. */
public record EngineStats(
    long activeMemtableBytes,
    int immutableMemtables,
    int sstables,
    int walSegments,
    long walFsyncs,
    long recoveredRecords,
    long truncatedWalBytes,
    long flushes,
    long bloomNegatives,
    long bloomFalsePositives) {}
