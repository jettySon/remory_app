package com.remory.app;

import static org.junit.Assert.*;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.Test;

public class ReviewReminderScheduleTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalTime EVENING = LocalTime.of(19, 0);

    @Test public void guestAlarmWaitsUntilTheChosenTimeWithAnActuallyDueCard() {
        Instant now = Instant.parse("2026-12-01T00:00:00Z");
        long beforeEvening = Instant.parse("2026-12-03T09:30:00Z").toEpochMilli();
        long afterEvening = Instant.parse("2026-12-03T10:30:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-12-03T10:00:00Z").toEpochMilli(),
                ReviewReminderSchedule.next(now, SEOUL, EVENING, "", beforeEvening));
        assertEquals(Instant.parse("2026-12-04T10:00:00Z").toEpochMilli(),
                ReviewReminderSchedule.next(now, SEOUL, EVENING, "", afterEvening));
    }

    @Test public void alarmSkipsAnEarlierTimeAndNeverReplaysATriggerFromYesterday() {
        long trigger = Instant.parse("2026-12-01T10:00:00Z").toEpochMilli();
        assertFalse(ReviewReminderSchedule.shouldDeliver(trigger, Instant.parse("2026-12-01T09:59:00Z"), SEOUL, ""));
        assertTrue(ReviewReminderSchedule.shouldDeliver(trigger, Instant.parse("2026-12-01T10:35:00Z"), SEOUL, ""));
        assertFalse(ReviewReminderSchedule.shouldDeliver(trigger, Instant.parse("2026-12-01T10:35:00Z"), SEOUL, "2026-12-01"));
        assertFalse(ReviewReminderSchedule.shouldDeliver(trigger, Instant.parse("2026-12-01T15:01:00Z"), SEOUL, ""));
        assertEquals(Instant.parse("2026-12-02T10:00:00Z").toEpochMilli(),
                ReviewReminderSchedule.next(Instant.parse("2026-12-01T08:00:00Z"), SEOUL, EVENING, "2026-12-01", null));
    }

    @Test public void schedulesInLocalTimeAcrossDaylightSaving() {
        assertEquals(Instant.parse("2026-03-08T23:00:00Z").toEpochMilli(),
                ReviewReminderSchedule.next(Instant.parse("2026-03-08T03:00:00Z"),
                        ZoneId.of("America/New_York"), EVENING, "", null));
    }
}
