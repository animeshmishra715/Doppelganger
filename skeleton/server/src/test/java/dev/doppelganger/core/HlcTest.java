package dev.doppelganger.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class HlcTest {

    private static final long T = Instant.parse("2026-11-20T10:15:03.120Z").toEpochMilli();

    @Test
    void textFormRoundTrips() {
        Hlc hlc = new Hlc(T, 4, "device-a");
        assertThat(hlc.format()).isEqualTo("2026-11-20T10:15:03.120Z-0004-device-a");
        assertThat(Hlc.parse(hlc.format())).isEqualTo(hlc);
    }

    @Test
    void ordersByTimeThenCounterThenDevice() {
        assertThat(new Hlc(T, 9, "z")).isLessThan(new Hlc(T + 1, 0, "a"));
        assertThat(new Hlc(T, 1, "z")).isLessThan(new Hlc(T, 2, "a"));
        assertThat(new Hlc(T, 1, "device-a")).isLessThan(new Hlc(T, 1, "device-b"));
    }

    @Test
    void ticksAlwaysIncreaseEvenWhenTheWallClockGoesBackwards() {
        AtomicLong wall = new AtomicLong(T);
        HlcClock clock = new HlcClock("device-a", wall::get);
        Hlc first = clock.tick();
        wall.set(T - 3_600_000);
        Hlc second = clock.tick();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void afterObservingARemoteTimestampLocalTicksSortAfterIt() {
        HlcClock slow = new HlcClock("device-a", () -> T - 5 * 3_600_000L);   // five hours behind
        Hlc remote = new Hlc(T, 7, "device-b");
        slow.observe(remote);
        assertThat(slow.tick()).isGreaterThan(remote);
    }
}
