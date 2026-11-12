package dev.doppelganger.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Hybrid logical clock timestamp: physical milliseconds, a logical counter and the node id.
 * Text form: {@code 2026-11-20T10:15:03.120Z-0004-device-a}. Ordering is by time, then counter,
 * then node id, so two timestamps are never equal unless they are the same event.
 */
public record Hlc(long millis, int counter, String node) implements Comparable<Hlc> {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static Hlc parse(String text) {
        try {
            int z = text.indexOf('Z');
            long millis = Instant.parse(text.substring(0, z + 1)).toEpochMilli();
            String rest = text.substring(z + 2);
            int dash = rest.indexOf('-');
            return new Hlc(millis, Integer.parseInt(rest.substring(0, dash), 16), rest.substring(dash + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid HLC timestamp: " + text);
        }
    }

    public String format() {
        return FORMAT.format(Instant.ofEpochMilli(millis)) + "-" + String.format("%04x", counter) + "-" + node;
    }

    @Override
    public int compareTo(Hlc o) {
        int c = Long.compare(millis, o.millis);
        if (c == 0) c = Integer.compare(counter, o.counter);
        return c != 0 ? c : node.compareTo(o.node);
    }

    /** Compares two text timestamps; {@code null} sorts lowest. */
    public static int compare(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        return parse(a).compareTo(parse(b));
    }

    public static String max(String a, String b) {
        return compare(a, b) >= 0 ? a : b;
    }

    @Override
    public String toString() {
        return format();
    }
}
