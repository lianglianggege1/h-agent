package com.h.backend.automation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomationScheduleTest {

    @Test
    void computesNextRunInConfiguredTimeZoneAndReturnsUtcInstant() {
        AutomationSchedule schedule = new AutomationSchedule("0 30 9 * * *", "Asia/Shanghai");

        Instant next = schedule.nextAfter(Instant.parse("2026-09-05T00:00:00Z"));

        assertEquals(Instant.parse("2026-09-05T01:30:00Z"), next);
    }

    @Test
    void rejectsSchedulesThatRunMoreOftenThanOncePerMinute() {
        assertThrows(IllegalArgumentException.class,
                () -> new AutomationSchedule("*/10 * * * * *", "Asia/Shanghai"));
    }

    @Test
    void rejectsUnknownTimeZone() {
        assertThrows(IllegalArgumentException.class,
                () -> new AutomationSchedule("0 0 9 * * *", "Mars/Olympus"));
    }
}
