package dev.doppelganger.core;

/**
 * One row of the append-only operation log. Server and devices build their state by applying
 * the same entries in position order, so they always end up identical.
 */
public record LogEntry(long position, Operation op, Disposition disposition, String note) {
}
