package com.surf.nursepro.nurse_pro_api.util;

import com.surf.nursepro.nurse_pro_api.entity.Shift;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/** Helpers for shift durations. Shifts that end at or before their start time cross midnight (e.g. 23:00-07:00). */
public final class ShiftTimes {
    private static final double DEFAULT_HOURS = 8.0;

    private ShiftTimes() {
    }

    public static double hours(Shift shift) {
        return hours(shift.getStartTime(), shift.getEndTime());
    }

    public static double hours(String start, String end) {
        if (start == null || end == null) {
            return DEFAULT_HOURS;
        }
        try {
            Duration duration = Duration.between(LocalTime.parse(start), LocalTime.parse(end));
            if (duration.isNegative() || duration.isZero()) {
                duration = duration.plusHours(24);
            }
            return duration.toMinutes() / 60.0;
        } catch (DateTimeParseException e) {
            return DEFAULT_HOURS;
        }
    }
}
