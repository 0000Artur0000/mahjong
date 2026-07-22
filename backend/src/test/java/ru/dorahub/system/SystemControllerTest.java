package ru.dorahub.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SystemControllerTest {

    @Test
    void returnsUtcServerTime() {
        var expected = Instant.parse("2026-07-22T09:00:00Z");
        var controller = new SystemController(Clock.fixed(expected, ZoneOffset.UTC));

        assertThat(controller.time().serverTime()).isEqualTo(expected);
    }
}

