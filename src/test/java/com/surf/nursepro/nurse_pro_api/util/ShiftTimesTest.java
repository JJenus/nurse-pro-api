package com.surf.nursepro.nurse_pro_api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShiftTimesTest {

    @Test
    void daytimeShift() {
        assertEquals(8.0, ShiftTimes.hours("07:00", "15:00"));
    }

    @Test
    void nightShiftCrossesMidnight() {
        assertEquals(8.0, ShiftTimes.hours("23:00", "07:00"));
    }

    @Test
    void sameStartAndEndMeansFullDay() {
        assertEquals(24.0, ShiftTimes.hours("07:00", "07:00"));
    }

    @Test
    void unparseableOrMissingFallsBackToEightHours() {
        assertEquals(8.0, ShiftTimes.hours("7am", "3pm"));
        assertEquals(8.0, ShiftTimes.hours(null, "15:00"));
    }

    @Test
    void crossesMidnightOnlyWhenEndIsNotAfterStart() {
        assertTrue(ShiftTimes.crossesMidnight("23:00", "07:00"));
        assertFalse(ShiftTimes.crossesMidnight("07:00", "15:00"));
        assertFalse(ShiftTimes.crossesMidnight("bad", "15:00"));
    }
}
