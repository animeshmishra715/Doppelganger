package dev.doppelganger.core;

import java.util.function.LongSupplier;

/**
 * A node's hybrid logical clock. {@link #tick()} stamps a local change; {@link #observe(Hlc)} is
 * called for every timestamp received from elsewhere, so later local changes always sort after
 * everything this node has seen, even when its wall clock is wrong.
 */
public final class HlcClock {

    private final String node;
    private final LongSupplier physicalMillis;
    private long millis;
    private int counter;

    public HlcClock(String node, LongSupplier physicalMillis) {
        this.node = node;
        this.physicalMillis = physicalMillis;
    }

    public synchronized Hlc tick() {
        long now = physicalMillis.getAsLong();
        if (now > millis) {
            millis = now;
            counter = 0;
        } else {
            counter++;
        }
        return new Hlc(millis, counter, node);
    }

    public synchronized void observe(Hlc remote) {
        long now = physicalMillis.getAsLong();
        long top = Math.max(now, Math.max(millis, remote.millis()));
        if (top == millis && top == remote.millis()) {
            counter = Math.max(counter, remote.counter()) + 1;
        } else if (top == millis) {
            counter++;
        } else if (top == remote.millis()) {
            counter = remote.counter() + 1;
        } else {
            counter = 0;
        }
        millis = top;
    }
}
