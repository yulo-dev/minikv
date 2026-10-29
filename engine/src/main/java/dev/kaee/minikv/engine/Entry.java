package dev.kaee.minikv.engine;

/** A key and its newest version, as returned by scans. */
// Arrays are kept by reference to avoid copies on the read and write paths. The public Engine API
// copies caller arrays on the way in. Record equals() compares array identity, so never compare
// these with equals(); compare fields with Arrays.equals instead.
@SuppressWarnings("ArrayRecordComponent")
public record Entry(byte[] key, VersionedValue value) {}
