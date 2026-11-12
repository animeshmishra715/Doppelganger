package dev.doppelganger.sim;

import dev.doppelganger.core.DomainSpec;
import dev.doppelganger.core.Operation;
import dev.doppelganger.core.PushResult;
import dev.doppelganger.core.SyncEngine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fake devices talking to the real {@link SyncEngine} over a fake network that can fail.
 * Used by the property-based tests (and later by the demo view).
 */
public final class Simulation {

    /** What goes wrong during one sync attempt. */
    public enum Fault {
        /** Push and pull both succeed. */
        NONE,
        /** The device has no signal: nothing reaches the server. */
        NO_CONNECTION,
        /** The connection drops halfway: the server gets the first half of the batch, the device gets nothing back. */
        DROPPED_HALFWAY,
        /** The server gets the whole batch but the acknowledgement is lost. */
        ACK_LOST,
        /** The batch is delivered twice. */
        DELIVERED_TWICE,
        /** The push succeeds but the pull fails. */
        PULL_FAILED,
        /** The push fails but the pull succeeds. */
        PUSH_FAILED
    }

    private final SyncEngine engine;
    private final DomainSpec spec;
    private final List<SimDevice> devices = new ArrayList<>();
    private long timeMillis = Instant.parse("2026-11-20T04:00:00Z").toEpochMilli();

    public Simulation(DomainSpec spec) {
        this.spec = spec;
        this.engine = new SyncEngine(spec);
    }

    public SimDevice addDevice(String id) {
        SimDevice device = new SimDevice(id, spec, () -> timeMillis);
        devices.add(device);
        return device;
    }

    public void sync(SimDevice device) {
        sync(device, Fault.NONE);
    }

    public void sync(SimDevice device, Fault fault) {
        List<Operation> batch = device.outbox();
        switch (fault) {
            case NO_CONNECTION -> {
                return;
            }
            case DROPPED_HALFWAY -> {
                engine.push(batch.subList(0, (batch.size() + 1) / 2));
                return;
            }
            case ACK_LOST -> {
                engine.push(batch);
                return;
            }
            case DELIVERED_TWICE -> {
                PushResult first = engine.push(batch);
                engine.push(batch);
                device.acknowledge(first.accepted());
            }
            case NONE, PULL_FAILED -> device.acknowledge(engine.push(batch).accepted());
            case PUSH_FAILED -> {
            }
        }
        if (fault != Fault.PULL_FAILED) {
            device.applyPulled(engine.pull(device.pulledPosition()));
        }
    }

    /** Every device syncs cleanly twice: the first round pushes everything, the second pulls everything. */
    public void syncAll() {
        for (int round = 0; round < 2; round++) {
            devices.forEach(this::sync);
        }
    }

    public void advanceTime(long millis) {
        timeMillis += millis;
    }

    public SyncEngine engine() {
        return engine;
    }

    public List<SimDevice> devices() {
        return List.copyOf(devices);
    }
}
