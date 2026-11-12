package dev.doppelganger.core;

import java.util.List;

/**
 * @param accepted   every op id in the batch that is now safely in the log (including resends)
 * @param duplicates the subset of {@code accepted} the server had already seen
 * @param appended   log entries created by this push, in order
 * @param head       log position after the push
 */
public record PushResult(List<String> accepted, List<String> duplicates, List<LogEntry> appended, long head) {
}
