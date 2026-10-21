package com.surf.nursepro.nurse_pro_api.service;

import com.surf.nursepro.nurse_pro_api.dto.ScheduleGenerationParams;
import com.surf.nursepro.nurse_pro_api.entity.Nurse;
import com.surf.nursepro.nurse_pro_api.entity.ScheduleConflict;
import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.enums.ScheduleConflictType;
import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import com.surf.nursepro.nurse_pro_api.util.ShiftTimes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.IsoFields;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleGeneratorTest {

    private final ScheduleGenerator generator = new ScheduleGenerator();
    private final ScheduleValidator validator = new ScheduleValidator();

    // ---- helpers -----------------------------------------------------------------------------------------

    private Nurse nurse(String id, int maxHoursPerWeek, LocalDate... unavailable) {
        Nurse n = new Nurse();
        n.setId(id);
        n.setFirstName("Nurse");
        n.setLastName(id);
        n.setMaxHoursPerWeek(maxHoursPerWeek);
        n.setPreferredShifts(new ArrayList<>());
        n.setUnavailableDates(new ArrayList<>(Arrays.asList(unavailable)));
        return n;
    }

    private List<Nurse> roster(int count, int maxHoursPerWeek) {
        List<Nurse> nurses = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            nurses.add(nurse(String.format("n%02d", i), maxHoursPerWeek));
        }
        return nurses;
    }

    private ScheduleGenerator.Request request(int year, int month, List<Nurse> nurses, long seed) {
        ScheduleGenerator.Request r = new ScheduleGenerator.Request();
        r.setYear(year);
        r.setMonth(month);
        r.setNurses(nurses);
        r.setSeed(seed);
        return r;
    }

    private Shift shift(LocalDate date, ShiftType type, String... nurseIds) {
        Shift s = new Shift();
        s.setDate(date);
        s.setType(type);
        s.setDepartment("General");
        switch (type) {
            case Day -> { s.setStartTime("07:00"); s.setEndTime("15:00"); }
            case Evening -> { s.setStartTime("15:00"); s.setEndTime("23:00"); }
            case Night -> { s.setStartTime("23:00"); s.setEndTime("07:00"); }
        }
        s.setAssignedNurses(new LinkedHashSet<>(Arrays.asList(nurseIds)));
        return s;
    }

    private LocalDateTime startOf(Shift s) {
        return s.getDate().atTime(LocalTime.parse(s.getStartTime()));
    }

    private LocalDateTime endOf(Shift s) {
        return startOf(s).plusMinutes(Math.round(ShiftTimes.hours(s) * 60));
    }

    /**
     * Independent re-check of every hard constraint, written separately from the generator so a bug in one
     * is not hidden by the same bug in the other.
     */
    private void assertHardConstraints(List<Shift> generated, Collection<Shift> history, List<Nurse> nurses,
                                       ScheduleGenerationParams.Constraints c) {
        Map<String, Nurse> byId = new HashMap<>();
        nurses.forEach(n -> byId.put(n.getId(), n));
        List<Shift> all = new ArrayList<>(generated);
        all.addAll(history);

        Map<String, List<Shift>> perNurse = new HashMap<>();
        for (Shift s : all) {
            for (String id : s.getAssignedNurses()) {
                perNurse.computeIfAbsent(id, k -> new ArrayList<>()).add(s);
            }
        }

        for (Map.Entry<String, List<Shift>> entry : perNurse.entrySet()) {
            Nurse nurse = byId.get(entry.getKey());
            assertNotNull(nurse, "assigned nurse must exist: " + entry.getKey());
            List<Shift> shifts = entry.getValue();
            shifts.sort(Comparator.comparing(this::startOf));

            Map<LocalDate, Shift> byDate = new HashMap<>();
            Map<Integer, Double> weekHours = new HashMap<>();
            for (int i = 0; i < shifts.size(); i++) {
                Shift s = shifts.get(i);
                String who = nurse.getId() + " on " + s.getDate() + " " + s.getType();

                assertNull(byDate.put(s.getDate(), s), who + ": two shifts on one day");

                if (generated.contains(s)) {
                    assertFalse(nurse.getUnavailableDates().contains(s.getDate()), who + ": unavailable");
                    assertFalse(nurse.getUnavailableDates().contains(endOf(s).toLocalDate()), who + ": unavailable next morning");
                }
                if (i > 0) {
                    double gap = Duration.between(endOf(shifts.get(i - 1)), startOf(s)).toMinutes() / 60.0;
                    assertTrue(gap >= c.getMinRestHours(), who + ": only " + gap + "h rest");
                }
                int week = s.getDate().get(IsoFields.WEEK_BASED_YEAR) * 100 + s.getDate().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                weekHours.merge(week, ShiftTimes.hours(s), Double::sum);
            }

            for (Shift s : shifts) {
                String who = nurse.getId() + " around " + s.getDate();
                // length of the run of consecutive working days that contains this shift
                int run = 1;
                for (LocalDate d = s.getDate().minusDays(1); byDate.containsKey(d); d = d.minusDays(1)) run++;
                for (LocalDate d = s.getDate().plusDays(1); byDate.containsKey(d); d = d.plusDays(1)) run++;
                assertTrue(run <= c.getMaxConsecutiveShifts(), who + ": " + run + " working days in a row");

                boolean night = s.getType() == ShiftType.Night;
                int typeRun = 1;
                for (LocalDate d = s.getDate().minusDays(1); byDate.containsKey(d)
                        && (byDate.get(d).getType() == ShiftType.Night) == night; d = d.minusDays(1)) typeRun++;
                for (LocalDate d = s.getDate().plusDays(1); byDate.containsKey(d)
                        && (byDate.get(d).getType() == ShiftType.Night) == night; d = d.plusDays(1)) typeRun++;
                assertTrue(typeRun <= (night ? c.getMaxConsecutiveNight() : c.getMaxConsecutiveDay()),
                        who + ": " + typeRun + " " + (night ? "night" : "day") + " shifts in a row");
            }

            if (nurse.getMaxHoursPerWeek() > 0) {
                weekHours.forEach((week, hours) -> assertTrue(hours <= nurse.getMaxHoursPerWeek() + c.getMaxOvertimeHours() + 1e-9,
                        nurse.getId() + ": " + hours + "h in week " + week));
            }
        }
    }

    private Map<String, Integer> shiftsPerNurse(List<Shift> shifts) {
        Map<String, Integer> counts = new TreeMap<>();
        shifts.forEach(s -> s.getAssignedNurses().forEach(id -> counts.merge(id, 1, Integer::sum)));
        return counts;
    }

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void neverViolatesAHardConstraintAcrossSeedsAndMonths() {
        List<Nurse> nurses = roster(14, 40);
        // include a leap-year February, a 30-day and a 31-day month
        int[][] months = {{2028, 2}, {2026, 11}, {2026, 12}};
        for (int[] ym : months) {
            for (long seed = 1; seed <= 12; seed++) {
                ScheduleGenerator.Result result = generator.generate(request(ym[0], ym[1], nurses, seed));
                assertHardConstraints(result.getShifts(), List.of(), nurses, new ScheduleGenerationParams.Constraints());
            }
        }
    }

    @Test
    void sameSeedGivesTheSameScheduleAndTheSeedIsReported() {
        List<Nurse> nurses = roster(12, 40);
        ScheduleGenerator.Result first = generator.generate(request(2026, 11, nurses, 42L));
        ScheduleGenerator.Result second = generator.generate(request(2026, 11, nurses, 42L));

        assertEquals(42L, first.getSeed());
        assertEquals(describe(first.getShifts()), describe(second.getShifts()));
    }

    private List<String> describe(List<Shift> shifts) {
        List<String> lines = new ArrayList<>();
        shifts.forEach(s -> lines.add(s.getDate() + " " + s.getType() + " " + s.getAssignedNurses()));
        return lines;
    }

    @Test
    void generatesOneSlotPerShiftTypePerDay() {
        ScheduleGenerator.Result feb = generator.generate(request(2028, 2, roster(12, 40), 1L));   // leap year
        assertEquals(29 * 3, feb.getShifts().size());

        ScheduleGenerator.Result dec = generator.generate(request(2026, 12, roster(12, 40), 1L));
        assertEquals(31 * 3, dec.getShifts().size());
        assertTrue(dec.getShifts().stream().allMatch(s -> s.getRequiredStaff() == 2));
    }

    @Test
    void staffsEveryShiftWhenThereAreEnoughNurses() {
        ScheduleGenerator.Result result = generator.generate(request(2026, 11, roster(14, 40), 3L));

        assertEquals(0, result.getUnfilledPositions());
        for (Shift s : result.getShifts()) {
            assertTrue(s.getAssignedNurses().size() >= 2, s.getDate() + " " + s.getType() + " is short");
            assertTrue(s.getAssignedNurses().size() <= 3, s.getDate() + " " + s.getType() + " is over the maximum");
        }
    }

    @Test
    void spreadsShiftsEvenlyBetweenIdenticalNurses() {
        ScheduleGenerator.Result result = generator.generate(request(2026, 11, roster(14, 40), 5L));
        Map<String, Integer> counts = shiftsPerNurse(result.getShifts());

        int min = Collections.min(counts.values());
        int max = Collections.max(counts.values());
        assertTrue(max - min <= 1, "shifts per nurse range from " + min + " to " + max);
    }

    @Test
    void tooFewNursesLeavesShiftsShortInsteadOfFailing() {
        List<Nurse> nurses = roster(3, 40);
        ScheduleGenerator.Result result = generator.generate(request(2026, 11, nurses, 1L));

        assertTrue(result.getUnfilledPositions() > 0);
        assertHardConstraints(result.getShifts(), List.of(), nurses, new ScheduleGenerationParams.Constraints());

        // the validator reports the gaps as conflicts
        Map<String, Nurse> byId = new HashMap<>();
        nurses.forEach(n -> byId.put(n.getId(), n));
        List<ScheduleConflict> conflicts = validator.validate("s", result.getShifts().stream().peek(s -> s.setId(s.getDate() + "" + s.getType())).toList(), byId);
        assertTrue(conflicts.stream().anyMatch(c -> c.getType() == ScheduleConflictType.understaffed));
    }

    @Test
    void nurseUnavailableForTheWholeMonthIsNeverScheduled() {
        List<Nurse> nurses = roster(12, 40);
        LocalDate[] november = new LocalDate[30];
        for (int i = 0; i < 30; i++) november[i] = LocalDate.of(2026, 11, 1).plusDays(i);
        nurses.set(0, nurse("n01", 40, november));

        ScheduleGenerator.Result result = generator.generate(request(2026, 11, nurses, 9L));

        assertFalse(shiftsPerNurse(result.getShifts()).containsKey("n01"));
        assertHardConstraints(result.getShifts(), List.of(), nurses, new ScheduleGenerationParams.Constraints());
    }

    @Test
    void generatedScheduleHasNoAvailabilityOrOverlapConflictsAccordingToTheValidator() {
        List<Nurse> nurses = roster(14, 40);
        nurses.set(1, nurse("n02", 40, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11)));
        ScheduleGenerator.Result result = generator.generate(request(2026, 11, nurses, 11L));

        Map<String, Nurse> byId = new HashMap<>();
        nurses.forEach(n -> byId.put(n.getId(), n));
        int[] counter = {0};
        result.getShifts().forEach(s -> s.setId("s" + counter[0]++));
        List<ScheduleConflict> conflicts = validator.validate("s", result.getShifts(), byId);

        assertTrue(conflicts.stream().noneMatch(c -> c.getType() != ScheduleConflictType.understaffed),
                "generator and validator disagree: " + conflicts);
    }

    @Test
    void weeklyHoursCapIsRespected() {
        List<Nurse> nurses = roster(16, 24);   // at most three 8-hour shifts a week
        ScheduleGenerator.Result result = generator.generate(request(2026, 11, nurses, 2L));
        assertHardConstraints(result.getShifts(), List.of(), nurses, new ScheduleGenerationParams.Constraints());
    }

    @Test
    void overtimeAllowanceRaisesTheWeeklyCap() {
        List<Nurse> nurses = roster(16, 24);
        ScheduleGenerationParams.Constraints c = new ScheduleGenerationParams.Constraints();
        c.setMaxOvertimeHours(8);
        ScheduleGenerator.Request r = request(2026, 11, nurses, 2L);
        r.setConstraints(c);

        ScheduleGenerator.Result result = generator.generate(r);
        assertHardConstraints(result.getShifts(), List.of(), nurses, c);
    }

    @Test
    void previousMonthNightShiftBlocksTheNextMorning() {
        List<Nurse> nurses = roster(14, 40);
        // n01 worked the night of 31 October, which ends at 07:00 on 1 November
        Shift previous = shift(LocalDate.of(2026, 10, 31), ShiftType.Night, "n01");
        ScheduleGenerator.Request r = request(2026, 11, nurses, 4L);
        r.setHistory(List.of(previous));

        ScheduleGenerator.Result result = generator.generate(r);

        for (Shift s : result.getShifts()) {
            if (s.getDate().equals(LocalDate.of(2026, 11, 1)) && s.getType() != ShiftType.Night) {
                assertFalse(s.getAssignedNurses().contains("n01"), "n01 would have less than 11h rest on " + s.getType());
            }
        }
        assertHardConstraints(result.getShifts(), List.of(previous), nurses, new ScheduleGenerationParams.Constraints());
    }

    @Test
    void historyCountsTowardsTheConsecutiveDaysLimit() {
        List<Nurse> nurses = roster(14, 40);
        // n01 already worked 28 to 31 October on days; with a limit of 5 in a row only one more day is allowed
        List<Shift> history = new ArrayList<>();
        for (int day = 28; day <= 31; day++) {
            history.add(shift(LocalDate.of(2026, 10, day), ShiftType.Day, "n01"));
        }
        ScheduleGenerator.Request r = request(2026, 11, nurses, 6L);
        r.setHistory(history);
        ScheduleGenerationParams.Constraints c = new ScheduleGenerationParams.Constraints();
        c.setMaxConsecutiveShifts(5);
        c.setMaxConsecutiveDay(7);
        r.setConstraints(c);

        ScheduleGenerator.Result result = generator.generate(r);
        assertHardConstraints(result.getShifts(), history, nurses, c);
    }

    @Test
    void lockedShiftsKeepTheirNursesAndExcludedNursesGetNothingNew() {
        List<Nurse> nurses = roster(14, 40);
        Shift locked = shift(LocalDate.of(2026, 11, 3), ShiftType.Day, "n05");
        ScheduleGenerator.Request r = request(2026, 11, nurses, 8L);
        r.setLocked(List.of(locked));
        r.setExcludedNurseIds(Set.of("n06"));

        ScheduleGenerator.Result result = generator.generate(r);

        Shift day3 = result.getShifts().stream()
                .filter(s -> s.getDate().equals(LocalDate.of(2026, 11, 3)) && s.getType() == ShiftType.Day)
                .findFirst().orElseThrow();
        assertTrue(day3.getAssignedNurses().contains("n05"));
        assertFalse(shiftsPerNurse(result.getShifts()).containsKey("n06"));
    }

    @Test
    void canGenerateJustPartOfAMonth() {
        ScheduleGenerator.Request r = request(2026, 11, roster(14, 40), 1L);
        r.setRangeStart(LocalDate.of(2026, 11, 16));
        r.setRangeEnd(LocalDate.of(2026, 11, 20));

        ScheduleGenerator.Result result = generator.generate(r);

        assertEquals(5 * 3, result.getShifts().size());
        assertEquals(LocalDate.of(2026, 11, 16), result.getShifts().get(0).getDate());
    }

    @Test
    void largeRosterIsFast() {
        List<Nurse> nurses = roster(80, 40);
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> generator.generate(request(2026, 12, nurses, 1L)));
    }
}
