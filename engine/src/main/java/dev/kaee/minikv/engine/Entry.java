package dev.kaee.minikv.engine;

/** A key and its newest version, as returned by scans. */
public record Entry(byte[] key, VersionedValue value) {}
