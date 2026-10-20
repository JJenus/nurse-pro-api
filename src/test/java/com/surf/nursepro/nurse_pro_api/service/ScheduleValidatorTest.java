package com.surf.nursepro.nurse_pro_api.service;

import com.surf.nursepro.nurse_pro_api.entity.Nurse;
import com.surf.nursepro.nurse_pro_api.entity.ScheduleConflict;
import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.enums.ScheduleConflictSeverity;
import com.surf.nursepro.nurse_pro_api.enums.ScheduleConflictType;
import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleValidatorTest {

    private final ScheduleValidator validator = new ScheduleValidator();
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    private Nurse nurse(String id, LocalDate... unavailable) {
        Nurse n = new Nurse();
        n.setId(id);
        n.setFirstName("Nurse");
        n.setLastName(id);
        n.setUnavailableDates(new ArrayList<>(Arrays.asList(unavailable)));
        return n;
    }

    private Shift shift(String id, LocalDate date, ShiftType type, int required, String... nurseIds) {
        Shift s = new Shift();
        s.setId(id);
        s.setDate(date);
        s.setType(type);
        s.setDepartment("General");
        s.setRequiredStaff(required);
        switch (type) {
            case Day -> { s.setStartTime("07:00"); s.setEndTime("15:00"); }
            case Evening -> { s.setStartTime("15:00"); s.setEndTime("23:00"); }
            case Night -> { s.setStartTime("23:00"); s.setEndTime("07:00"); }
        }
        s.setAssignedNurses(new LinkedHashSet<>(Arrays.asList(nurseIds)));
        return s;
    }

    private Map<String, Nurse> nurses(Nurse... list) {
        Map<String, Nurse> map = new HashMap<>();
        for (Nurse n : list) {
            map.put(n.getId(), n);
        }
        return map;
    }

    @Test
    void cleanScheduleHasNoConflicts() {
        List<Shift> shifts = List.of(shift("s1", MONDAY, ShiftType.Day, 2, "a", "b"));
        assertTrue(validator.validate("sch", shifts, nurses(nurse("a"), nurse("b"))).isEmpty());
    }

    @Test
    void understaffedShiftIsHighAndEmptyShiftIsCritical() {
        List<Shift> shifts = List.of(
                shift("s1", MONDAY, ShiftType.Day, 2, "a"),
                shift("s2", MONDAY, ShiftType.Evening, 2));
        List<ScheduleConflict> result = validator.validate("sch", shifts, nurses(nurse("a")));

        assertEquals(2, result.size());
        assertEquals(ScheduleConflictSeverity.high, byShift(result, "s1").getSeverity());
        assertEquals(ScheduleConflictSeverity.critical, byShift(result, "s2").getSeverity());
        assertEquals("sch", result.get(0).getScheduleId());
    }

    @Test
    void unavailableNurseIsFlagged() {
        List<Shift> shifts = List.of(shift("s1", MONDAY, ShiftType.Day, 1, "a"));
        List<ScheduleConflict> result = validator.validate("sch", shifts, nurses(nurse("a", MONDAY)));

        assertEquals(1, result.size());
        assertEquals(ScheduleConflictType.availability, result.get(0).getType());
        assertEquals("a", result.get(0).getNurseId());
    }

    @Test
    void nightShiftAlsoOccupiesTheNextDay() {
        List<Shift> shifts = List.of(shift("s1", MONDAY, ShiftType.Night, 1, "a"));
        List<ScheduleConflict> result = validator.validate("sch", shifts,
                nurses(nurse("a", MONDAY.plusDays(1))));

        assertEquals(1, result.size());
        assertEquals(ScheduleConflictType.availability, result.get(0).getType());
    }

    @Test
    void doubleBookingOnTheSameDayIsFlaggedOnBothShifts() {
        List<Shift> shifts = List.of(
                shift("s1", MONDAY, ShiftType.Day, 1, "a"),
                shift("s2", MONDAY, ShiftType.Evening, 1, "a"));
        List<ScheduleConflict> result = validator.validate("sch", shifts, nurses(nurse("a")));

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(c -> c.getType() == ScheduleConflictType.overlap));
    }

    @Test
    void unknownNurseIsFlagged() {
        List<Shift> shifts = List.of(shift("s1", MONDAY, ShiftType.Day, 1, "ghost"));
        List<ScheduleConflict> result = validator.validate("sch", shifts, nurses());

        assertEquals(1, result.size());
        assertEquals("ghost", result.get(0).getNurseId());
    }

    @Test
    void candidateValidationOnlyReturnsConflictsForTheCandidate() {
        Shift existing = shift("s1", MONDAY, ShiftType.Day, 1, "a");
        Shift unrelatedUnderstaffed = shift("s2", MONDAY.plusDays(1), ShiftType.Day, 2);
        Shift candidate = shift("ignored", MONDAY, ShiftType.Evening, 1, "a");

        List<ScheduleConflict> result = validator.validateCandidate("sch", candidate, null,
                List.of(existing, unrelatedUnderstaffed), nurses(nurse("a")));

        assertEquals(1, result.size());
        assertEquals(ScheduleConflictType.overlap, result.get(0).getType());
        assertNull(result.get(0).getShiftId());
    }

    private ScheduleConflict byShift(List<ScheduleConflict> conflicts, String shiftId) {
        return conflicts.stream().filter(c -> shiftId.equals(c.getShiftId())).findFirst().orElseThrow();
    }
}
