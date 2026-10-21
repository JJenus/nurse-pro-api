package com.surf.nursepro.nurse_pro_api.service;

import com.surf.nursepro.nurse_pro_api.dto.RescheduleResult;
import com.surf.nursepro.nurse_pro_api.entity.Nurse;
import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.enums.RescheduleMode;
import com.surf.nursepro.nurse_pro_api.enums.ScheduleConflictType;
import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReschedulePlannerTest {

    private final ScheduleGenerator generator = new ScheduleGenerator();
    private final ReschedulePlanner planner = new ReschedulePlanner(generator, new ScheduleValidator());

    private static final LocalDate NOV_16 = LocalDate.of(2026, 11, 16);
    private static final LocalDate NOV_30 = LocalDate.of(2026, 11, 30);

    // ---- helpers -----------------------------------------------------------------------------------------

    private Nurse nurse(String id, LocalDate... unavailable) {
        Nurse n = new Nurse();
        n.setId(id);
        n.setFirstName("Nurse");
        n.setLastName(id);
        n.setMaxHoursPerWeek(40);
        n.setPreferredShifts(new ArrayList<>());
        n.setUnavailableDates(new ArrayList<>(Arrays.asList(unavailable)));
        return n;
    }

    private List<Nurse> roster(int count) {
        List<Nurse> nurses = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            nurses.add(nurse(String.format("n%02d", i)));
        }
        return nurses;
    }

    /** A complete, valid schedule for November 2026 as it would be stored (shifts have ids). */
    private List<Shift> existingSchedule(List<Nurse> nurses, long seed) {
        ScheduleGenerator.Request r = new ScheduleGenerator.Request();
        r.setYear(2026);
        r.setMonth(11);
        r.setNurses(nurses);
        r.setSeed(seed);
        List<Shift> shifts = generator.generate(r).getShifts();
        int i = 0;
        for (Shift s : shifts) {
            s.setId("s" + i++);
        }
        return shifts;
    }

    private ReschedulePlanner.Input input(List<Nurse> nurses, List<Shift> shifts, LocalDate from, LocalDate to,
                                          RescheduleMode mode) {
        ReschedulePlanner.Input in = new ReschedulePlanner.Input();
        in.setScheduleId("sched");
        in.setYear(2026);
        in.setMonth(11);
        in.setNurses(nurses);
        in.setScheduleShifts(shifts);
        in.setFrom(from);
        in.setTo(to);
        in.setMode(mode);
        in.setSeed(1L);
        return in;
    }

    private Shift find(List<Shift> shifts, LocalDate date, ShiftType type) {
        return shifts.stream().filter(s -> s.getDate().equals(date) && s.getType() == type).findFirst().orElseThrow();
    }

    private RescheduleResult.ShiftChange changeFor(RescheduleResult result, String shiftId) {
        return result.getChanges().stream().filter(c -> shiftId.equals(c.getShiftId())).findFirst().orElse(null);
    }

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void nothingChangesWhenEveryShiftIsAlreadyStaffedAndValid() {
        List<Nurse> nurses = roster(14);
        List<Shift> shifts = existingSchedule(nurses, 1L);

        RescheduleResult result = planner.plan(input(nurses, shifts, NOV_16, NOV_30, RescheduleMode.FILL_GAPS)).getResult();

        assertTrue(result.getChanges().isEmpty());
        assertEquals(0, result.getUnfilledPositions());
    }

    @Test
    void nurseWhoBecameUnavailableIsReplacedOnlyWhereTheShiftFallsBelowItsMinimum() {
        List<Nurse> nurses = roster(14);
        List<Shift> shifts = existingSchedule(nurses, 1L);
        Shift target = find(shifts, LocalDate.of(2026, 11, 20), ShiftType.Day);
        // shrink the shift to exactly its minimum so losing a nurse leaves a gap
        List<String> assigned = new ArrayList<>(target.getAssignedNurses());
        target.setAssignedNurses(new LinkedHashSet<>(assigned.subList(0, 2)));
        String leaving = assigned.get(0);
        nurses.replaceAll(n -> n.getId().equals(leaving) ? nurse(leaving, LocalDate.of(2026, 11, 20)) : n);

        RescheduleResult result = planner.plan(input(nurses, shifts, NOV_16, NOV_30, RescheduleMode.FILL_GAPS)).getResult();

        RescheduleResult.ShiftChange change = changeFor(result, target.getId());
        assertNotNull(change);
        assertEquals(List.of(leaving), change.getRemoved());
        assertEquals(1, change.getAdded().size());
        assertNotEquals(leaving, change.getAdded().get(0));
        assertFalse(change.isNewShift());
        assertEquals(0, result.getUnfilledPositions());
    }

    @Test
    void excludedNurseIsRemovedFromEveryShiftInTheRange() {
        List<Nurse> nurses = roster(14);
        List<Shift> shifts = existingSchedule(nurses, 2L);
        ReschedulePlanner.Input in = input(nurses, shifts, NOV_16, NOV_30, RescheduleMode.FILL_GAPS);
        in.setExcludedNurseIds(Set.of("n03"));

        RescheduleResult result = planner.plan(in).getResult();

        for (Shift s : shifts) {
            boolean inRange = !s.getDate().isBefore(NOV_16);
            RescheduleResult.ShiftChange change = changeFor(result, s.getId());
            if (inRange && s.getAssignedNurses().contains("n03")) {
                assertNotNull(change, "expected a change on " + s.getDate() + " " + s.getType());
                assertTrue(change.getRemoved().contains("n03"));
                assertFalse(change.getAdded().contains("n03"));
            } else if (!inRange) {
                assertNull(change, "dates before the range must not change");
            }
        }
        // nobody is added back beyond what is needed to reach the minimum
        for (RescheduleResult.ShiftChange c : result.getChanges()) {
            assertFalse(c.getAdded().contains("n03"));
        }
    }

    @Test
    void gapFillingDoesNotCreateMissingShifts() {
        List<Nurse> nurses = roster(14);
        List<Shift> shifts = existingSchedule(nurses, 3L);
        Shift missing = find(shifts, LocalDate.of(2026, 11, 22), ShiftType.Night);
        shifts.remove(missing);

        Plan2 plan = new Plan2(planner.plan(input(nurses, shifts, NOV_16, NOV_30, RescheduleMode.FILL_GAPS)));

        assertEquals(0, plan.result().getShiftsCreated());
        assertTrue(plan.plan.getNewShiftsByKey().isEmpty());
    }

    @Test
    void rebuildingTheRangeRecreatesMissingShifts() {
        List<Nurse> nurses = roster(14);
        List<Shift> shifts = existingSchedule(nurses, 3L);
        Shift missing = find(shifts, LocalDate.of(2026, 11, 22), ShiftType.Night);
        shifts.remove(missing);

        Plan2 plan = new Plan2(planner.plan(input(nurses, shifts, NOV_16, NOV_30, RescheduleMode.REBUILD_RANGE)));

        assertEquals(1, plan.result().getShiftsCreated());
        assertTrue(plan.plan.getNewShiftsByKey().containsKey(ScheduleGenerator.slotKey(LocalDate.of(2026, 11, 22), ShiftType.Night)));
    }

    @Test
    void rebuildKeepsLockedShiftsAndNeverTouchesEarlierDates() {
        List<Nurse> nurses = roster(14);
        List<Shift> shifts = existingSchedule(nurses, 4L);
        Shift locked = find(shifts, LocalDate.of(2026, 11, 18), ShiftType.Evening);
        ReschedulePlanner.Input in = input(nurses, shifts, NOV_16, NOV_30, RescheduleMode.REBUILD_RANGE);
        in.setLockedShiftIds(Set.of(locked.getId()));
        in.setSeed(99L);   // a different seed so the rebuilt shifts are very likely to differ

        RescheduleResult result = planner.plan(in).getResult();

        assertNull(changeFor(result, locked.getId()), "locked shift must not change");
        assertFalse(result.getChanges().isEmpty(), "the rest of the range should have been rebuilt");
        for (RescheduleResult.ShiftChange c : result.getChanges()) {
            assertFalse(c.getDate().isBefore(NOV_16));
        }
    }

    @Test
    void planDoesNotModifyTheStoredShifts() {
        List<Nurse> nurses = roster(14);
        List<Shift> shifts = existingSchedule(nurses, 5L);
        Map<String, Set<String>> before = new HashMap<>();
        shifts.forEach(s -> before.put(s.getId(), new LinkedHashSet<>(s.getAssignedNurses())));
        ReschedulePlanner.Input in = input(nurses, shifts, NOV_16, NOV_30, RescheduleMode.REBUILD_RANGE);
        in.setExcludedNurseIds(Set.of("n01"));

        planner.plan(in);

        shifts.forEach(s -> assertEquals(before.get(s.getId()), s.getAssignedNurses()));
    }

    @Test
    void nextMonthsShiftsConstrainTheEndOfTheRange() {
        List<Nurse> nurses = roster(14);
        List<Shift> shifts = existingSchedule(nurses, 6L);
        // n01 starts the day shift on 1 December; a 30 November evening or night would leave under 11h of rest
        Shift december = new Shift();
        december.setId("dec1");
        december.setDate(LocalDate.of(2026, 12, 1));
        december.setType(ShiftType.Day);
        december.setDepartment("General");
        december.setStartTime("07:00");
        december.setEndTime("15:00");
        december.setAssignedNurses(new LinkedHashSet<>(List.of("n01")));
        ReschedulePlanner.Input in = input(nurses, shifts, LocalDate.of(2026, 11, 28), NOV_30, RescheduleMode.REBUILD_RANGE);
        in.setNeighbourShifts(List.of(december));

        ReschedulePlanner.Plan plan = planner.plan(in);
        Map<String, Set<String>> after = new HashMap<>();
        for (Shift s : shifts) {
            Set<String> set = new LinkedHashSet<>(s.getAssignedNurses());
            RescheduleResult.ShiftChange c = changeFor(plan.getResult(), s.getId());
            if (c != null) {
                set.removeAll(c.getRemoved());
                set.addAll(c.getAdded());
            }
            after.put(s.getDate() + "|" + s.getType(), set);
        }

        assertFalse(after.get(LocalDate.of(2026, 11, 30) + "|" + ShiftType.Evening).contains("n01"));
        assertFalse(after.get(LocalDate.of(2026, 11, 30) + "|" + ShiftType.Night).contains("n01"));
    }

    @Test
    void shortShiftsAreReportedAsConflictsWhenThereAreNotEnoughNurses() {
        List<Nurse> nurses = roster(3);
        List<Shift> shifts = existingSchedule(nurses, 7L);

        RescheduleResult result = planner.plan(input(nurses, shifts, NOV_16, NOV_30, RescheduleMode.REBUILD_RANGE)).getResult();

        assertTrue(result.getUnfilledPositions() > 0);
        assertTrue(result.getConflicts().stream().anyMatch(c -> c.getType() == ScheduleConflictType.understaffed));
    }

    @Test
    void sameSeedGivesTheSamePreview() {
        List<Nurse> nurses = roster(14);
        List<Shift> shifts = existingSchedule(nurses, 8L);
        ReschedulePlanner.Input in = input(nurses, shifts, NOV_16, NOV_30, RescheduleMode.REBUILD_RANGE);
        in.setSeed(1234L);

        RescheduleResult first = planner.plan(in).getResult();
        RescheduleResult second = planner.plan(in).getResult();

        assertEquals(1234L, first.getSeed());
        assertEquals(first.getChanges(), second.getChanges());
    }

    /** Small helper so tests can read the result and the plan from one object. */
    private static class Plan2 {
        final ReschedulePlanner.Plan plan;
        Plan2(ReschedulePlanner.Plan plan) { this.plan = plan; }
        RescheduleResult result() { return plan.getResult(); }
    }
}
