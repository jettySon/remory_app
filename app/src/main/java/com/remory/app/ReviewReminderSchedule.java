package com.remory.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

final class ReviewReminderSchedule {
    static long next(Instant now, ZoneId zone, LocalTime time, String lastDay, Long guestDueAt) {
        LocalDate day = now.atZone(zone).toLocalDate();
        if (guestDueAt != null) {
            LocalDate dueDay = Instant.ofEpochMilli(guestDueAt).atZone(zone).toLocalDate();
            if (dueDay.isAfter(day)) day = dueDay;
        }
        Instant at = day.atTime(time).atZone(zone).toInstant();
        if (!at.isAfter(now) || day.toString().equals(lastDay)
                || (guestDueAt != null && at.toEpochMilli() < guestDueAt)) {
            at = day.plusDays(1).atTime(time).atZone(zone).toInstant();
        }
        return at.toEpochMilli();
    }

    static boolean shouldDeliver(long trigger, Instant now, ZoneId zone, String lastDay) {
        LocalDate day = now.atZone(zone).toLocalDate();
        return trigger <= now.toEpochMilli() && !day.toString().equals(lastDay)
                && day.equals(Instant.ofEpochMilli(trigger).atZone(zone).toLocalDate());
    }
}
