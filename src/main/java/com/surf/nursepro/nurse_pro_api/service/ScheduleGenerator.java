package com.surf.nursepro.nurse_pro_api.service;

import com.surf.nursepro.nurse_pro_api.dto.ScheduleGenerationParams;
import com.surf.nursepro.nurse_pro_api.entity.Nurse;
import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import com.surf.nursepro.nurse_pro_api.util.ShiftTimes;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Builds the shifts of a month. A pure function of its input: no repositories, nothing persisted, and the same
 * request (including {@code seed}) always produces the same result.
 *
 * <p>Hard constraints, never violated: nurse unavailable dates (including the morning after a night shift), one shift
 * per nurse per day, minimum rest between shifts, maximum consecutive working days, maximum consecutive day-type and
 * night-type shifts, and the weekly hours cap (ISO weeks, so a week that straddles two months is counted correctly
 * when {@code history} is supplied). If they make a slot impossible to staff, the slot is left short and the
 * validator reports it as a conflict; the generator never throws for that.
 *
 * <p>Method: (1) fill every slot to its minimum, always working on the most constrained slot first; (2) raise all
 * slots evenly towards the maximum; (3) repair slots still short by taking a nurse from a slot that has surplus;
 * (4) balance hours between the busiest and quietest nurses. Choices between eligible nurses are scored (fairness,
 * nights and weekends, stated shift preferences, working in blocks) with a small seeded jitter as tie-break.
 *
 * <p>For rescheduling, {@code locked} shifts keep their nurses, {@code excludedNurseIds} are not assigned anything new,
 * and {@code history} (shifts outside the generated range) still counts for rest and consecutive-day rules.
 */
@Component
public class ScheduleGenerator {

    static final String DEPARTMENT = "General";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final double SHIFT_HOURS = 8.0;
    private static final int BALANCE_ITERATIONS = 500;

    private static final double FAIR_WEIGHT = 3.0;
    private static final double NIGHT_WEIGHT = 1.5;
    private static final double WEEKEND_WEIGHT = 1.0;
    private static final double PREFERENCE_WEIGHT = 2.0;
    private static final double BLOCK_WEIGHT = 1.0;
    private static final double JITTER = 0.5;

    /** Input. Everything except year, month and nurses is optional. */
    public static class Request {
        private int year;
        private int month;
        private List<Nurse> nurses = new ArrayList<>();
        private ScheduleGenerationParams.Constraints constraints = new ScheduleGenerationParams.Constraints();
        private Long seed;
        private LocalDate rangeStart;
        private LocalDate rangeEnd;
        private Collection<Shift> history = new ArrayList<>();
        private Collection<Shift> locked = new ArrayList<>();
        private Set<String> excludedNurseIds = new HashSet<>();
        private boolean fillToMaximum = true;
        private Set<String> onlySlotKeys;
        private Map<String, Integer> requiredStaffBySlot = new HashMap<>();
        private Map<String, Collection<String>> requirementsBySlot = new HashMap<>();

        public int getYear() { return year; }
        public void setYear(int year) { this.year = year; }
        public int getMonth() { return month; }
        public void setMonth(int month) { this.month = month; }
        public List<Nurse> getNurses() { return nurses; }
        public void setNurses(List<Nurse> nurses) { this.nurses = nurses; }
        public ScheduleGenerationParams.Constraints getConstraints() { return constraints; }
        public void setConstraints(ScheduleGenerationParams.Constraints constraints) { this.constraints = constraints; }
        public Long getSeed() { return seed; }
        public void setSeed(Long seed) { this.seed = seed; }
        /** First date to generate (default: first day of the month). */
        public LocalDate getRangeStart() { return rangeStart; }
        public void setRangeStart(LocalDate rangeStart) { this.rangeStart = rangeStart; }
        /** Last date to generate, inclusive (default: last day of the month). */
        public LocalDate getRangeEnd() { return rangeEnd; }
        public void setRangeEnd(LocalDate rangeEnd) { this.rangeEnd = rangeEnd; }
        /** Existing shifts outside the range (e.g. the previous month); they only constrain new assignments. */
        public Collection<Shift> getHistory() { return history; }
        public void setHistory(Collection<Shift> history) { this.history = history; }
        /** Existing shifts inside the range whose nurses must stay where they are. */
        public Collection<Shift> getLocked() { return locked; }
        public void setLocked(Collection<Shift> locked) { this.locked = locked; }
        public Set<String> getExcludedNurseIds() { return excludedNurseIds; }
        public void setExcludedNurseIds(Set<String> excludedNurseIds) { this.excludedNurseIds = excludedNurseIds; }
        /** When false only the minimum staffing is filled (gap filling); default true. */
        public boolean isFillToMaximum() { return fillToMaximum; }
        public void setFillToMaximum(boolean fillToMaximum) { this.fillToMaximum = fillToMaximum; }
        /** When set, only slots with these keys (see {@link #slotKey}) are created; null creates every slot. */
        public Set<String> getOnlySlotKeys() { return onlySlotKeys; }
        public void setOnlySlotKeys(Set<String> onlySlotKeys) { this.onlySlotKeys = onlySlotKeys; }
        /** Per-slot minimum staffing that overrides the constraint default (key: {@link #slotKey}). */
        public Map<String, Collection<String>> getRequirementsBySlot() { return requirementsBySlot; }
        /** Qualifications a slot needs (key: slot key); only nurses whose specializations include all of them are eligible. */
        public void setRequirementsBySlot(Map<String, Collection<String>> requirementsBySlot) { this.requirementsBySlot = requirementsBySlot; }
        public Map<String, Integer> getRequiredStaffBySlot() { return requiredStaffBySlot; }
        public void setRequiredStaffBySlot(Map<String, Integer> requiredStaffBySlot) { this.requiredStaffBySlot = requiredStaffBySlot; }
    }

    public static class Result {
        private final List<Shift> shifts;
        private final long seed;
        private final int unfilledPositions;

        Result(List<Shift> shifts, long seed, int unfilledPositions) {
            this.shifts = shifts;
            this.seed = seed;
            this.unfilledPositions = unfilledPositions;
        }

        /** New, unsaved shifts (no schedule set) in date and type order. */
        public List<Shift> getShifts() { return shifts; }
        /** The seed that was used; pass it back in to reproduce this result. */
        public long getSeed() { return seed; }
        /** Total nurse positions below the minimum across all slots. */
        public int getUnfilledPositions() { return unfilledPositions; }
    }

    /** Identifies a slot: date and shift type (there is one General shift per type per day). */
    public static String slotKey(LocalDate date, ShiftType type) {
        return date + "|" + type;
    }

    // ------------------------------------------------------------------------------------------------------------

    public Result generate(Request request) {
        if (request.getMonth() < 1 || request.getMonth() > 12) {
            throw new IllegalArgumentException("month must be between 1 and 12");
        }
        long seed = request.getSeed() != null ? request.getSeed() : new Random().nextLong();
        Context ctx = new Context(request, new Random(seed));

        LocalDate first = LocalDate.of(request.getYear(), request.getMonth(), 1);
        LocalDate start = request.getRangeStart() != null ? request.getRangeStart() : first;
        LocalDate end = request.getRangeEnd() != null ? request.getRangeEnd() : first.withDayOfMonth(first.lengthOfMonth());

        buildTimelines(ctx, request);
        addHistory(ctx, request.getHistory());
        buildSlots(ctx, request, start, end);

        fillToMinimum(ctx);
        if (request.isFillToMaximum()) {
            raiseTowardsMaximum(ctx);
        }
        repairShortSlots(ctx);
        balanceHours(ctx);

        List<Shift> shifts = new ArrayList<>();
        int unfilled = 0;
        for (Slot slot : ctx.slots) {
            Shift shift = new Shift();
            shift.setDate(slot.date);
            shift.setType(slot.type);
            shift.setDepartment(DEPARTMENT);
            shift.setStartTime(slot.start.toLocalTime().format(HH_MM));
            shift.setEndTime(slot.end.toLocalTime().format(HH_MM));
            shift.setRequiredStaff(slot.min);
            shift.setAssignedNurses(new LinkedHashSet<>(slot.assigned));
            shifts.add(shift);
            unfilled += Math.max(0, slot.min - slot.assigned.size());
        }
        return new Result(shifts, seed, unfilled);
    }

    // ---- model ------------------------------------------------------------------------------------------------

    private static final class Slot {
        final LocalDate date;
        final ShiftType type;
        final LocalDateTime start;
        final LocalDateTime end;
        final double hours;
        final boolean weekend;
        final boolean night;
        final int weekKey;
        final int min;
        final int max;
        final boolean history;
        final Set<String> assigned = new LinkedHashSet<>();
        final Set<String> fixed = new HashSet<>();
        /** Lower-case qualifications a nurse must have to work this slot. */
        final Set<String> requirements = new HashSet<>();

        Slot(LocalDate date, ShiftType type, LocalDateTime start, double hours, int min, int max, boolean history) {
            this.date = date;
            this.type = type;
            this.start = start;
            this.hours = hours;
            this.end = start.plusMinutes(Math.round(hours * 60));
            this.weekend = date.getDayOfWeek().getValue() >= 6;
            this.night = type == ShiftType.Night;
            this.weekKey = date.get(IsoFields.WEEK_BASED_YEAR) * 100 + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            this.min = min;
            this.max = max;
            this.history = history;
        }
    }

    private static final class Timeline {
        final Nurse nurse;
        final String id;
        final Set<LocalDate> unavailable;
        final List<Slot> slots = new ArrayList<>();
        final Map<LocalDate, Slot> byDate = new HashMap<>();
        double monthHours;
        int nightShifts;
        int weekendShifts;

        Timeline(Nurse nurse) {
            this.nurse = nurse;
            this.id = nurse.getId();
            this.unavailable = nurse.getUnavailableDates() == null
                    ? new HashSet<>() : new HashSet<>(nurse.getUnavailableDates());
        }
    }

    private static final class Context {
        final Random rng;
        final double minRestHours;
        final int maxConsecutive;
        final int maxDayRun;
        final int maxNightRun;
        final int overtime;
        final int minNurses;
        final int maxNurses;
        final Set<String> excluded;
        final List<Timeline> timelines = new ArrayList<>();
        final Map<String, Timeline> byId = new HashMap<>();
        final List<Slot> slots = new ArrayList<>();

        Context(Request r, Random rng) {
            ScheduleGenerationParams.Constraints c = r.getConstraints() != null
                    ? r.getConstraints() : new ScheduleGenerationParams.Constraints();
            this.rng = rng;
            this.minRestHours = c.getMinRestHours();
            this.maxConsecutive = c.getMaxConsecutiveShifts();
            this.maxDayRun = c.getMaxConsecutiveDay();
            this.maxNightRun = c.getMaxConsecutiveNight();
            this.overtime = c.getMaxOvertimeHours();
            this.minNurses = c.getMinNursesPerShift();
            this.maxNurses = Math.max(c.getMaxNursesPerShift(), c.getMinNursesPerShift());
            this.excluded = r.getExcludedNurseIds() != null ? r.getExcludedNurseIds() : new HashSet<>();
        }
    }

    // ---- setup ------------------------------------------------------------------------------------------------

    private void buildTimelines(Context ctx, Request request) {
        List<Nurse> sorted = new ArrayList<>(request.getNurses());
        sorted.sort(Comparator.comparing(Nurse::getId));
        for (Nurse nurse : sorted) {
            Timeline t = new Timeline(nurse);
            ctx.timelines.add(t);
            ctx.byId.put(t.id, t);
        }
    }

    private void addHistory(Context ctx, Collection<Shift> history) {
        if (history == null) {
            return;
        }
        for (Shift shift : history) {
            if (shift.getDate() == null || shift.getAssignedNurses() == null) {
                continue;
            }
            Slot slot = slotFromShift(shift, ctx, true);
            for (String nurseId : shift.getAssignedNurses()) {
                Timeline t = ctx.byId.get(nurseId);
                // history of a nurse who is already working that day (or unknown) is simply ignored
                if (t != null && !t.byDate.containsKey(slot.date)) {
                    slot.assigned.add(nurseId);
                    place(t, slot);
                }
            }
        }
    }

    private Slot slotFromShift(Shift shift, Context ctx, boolean history) {
        ShiftType type = shift.getType() != null ? shift.getType() : ShiftType.Day;
        LocalTime startTime = parse(shift.getStartTime(), defaultStart(type));
        double hours = ShiftTimes.hours(shift.getStartTime(), shift.getEndTime());
        return new Slot(shift.getDate(), type, shift.getDate().atTime(startTime), hours,
                ctx.minNurses, ctx.maxNurses, history);
    }

    private void buildSlots(Context ctx, Request request, LocalDate start, LocalDate end) {
        Map<String, Shift> lockedByKey = new HashMap<>();
        if (request.getLocked() != null) {
            for (Shift shift : request.getLocked()) {
                if (shift.getDate() != null && shift.getType() != null) {
                    lockedByKey.put(slotKey(shift.getDate(), shift.getType()), shift);
                }
            }
        }

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            for (ShiftType type : ShiftType.values()) {
                String key = slotKey(date, type);
                if (request.getOnlySlotKeys() != null && !request.getOnlySlotKeys().contains(key)) {
                    continue;
                }
                Integer override = request.getRequiredStaffBySlot() == null ? null : request.getRequiredStaffBySlot().get(key);
                int min = override != null && override > 0 ? override : ctx.minNurses;
                Slot slot = new Slot(date, type, date.atTime(defaultStart(type)), SHIFT_HOURS,
                        min, Math.max(ctx.maxNurses, min), false);
                ctx.slots.add(slot);
                Collection<String> required = request.getRequirementsBySlot() == null ? null : request.getRequirementsBySlot().get(key);
                if (required != null) {
                    for (String r : required) {
                        if (r != null && !r.isBlank()) slot.requirements.add(r.trim().toLowerCase());
                    }
                }

                Shift lockedShift = lockedByKey.get(key);
                if (lockedShift != null && lockedShift.getAssignedNurses() != null) {
                    for (String nurseId : lockedShift.getAssignedNurses()) {
                        Timeline t = ctx.byId.get(nurseId);
                        if (t != null && !t.byDate.containsKey(date)) {
                            slot.assigned.add(nurseId);
                            slot.fixed.add(nurseId);
                            place(t, slot);
                        }
                    }
                }
            }
        }
    }

    private static LocalTime defaultStart(ShiftType type) {
        switch (type) {
            case Evening:
                return LocalTime.of(15, 0);
            case Night:
                return LocalTime.of(23, 0);
            case Day:
            default:
                return LocalTime.of(7, 0);
        }
    }

    private static LocalTime parse(String value, LocalTime fallback) {
        try {
            return value == null ? fallback : LocalTime.parse(value);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---- assignment bookkeeping --------------------------------------------------------------------------------

    private void place(Timeline t, Slot slot) {
        t.slots.add(slot);
        t.byDate.put(slot.date, slot);
        if (!slot.history) {
            t.monthHours += slot.hours;
            if (slot.night) t.nightShifts++;
            if (slot.weekend) t.weekendShifts++;
        }
    }

    private void assign(Timeline t, Slot slot) {
        slot.assigned.add(t.id);
        place(t, slot);
    }

    private void unassign(Timeline t, Slot slot) {
        slot.assigned.remove(t.id);
        t.slots.remove(slot);
        t.byDate.remove(slot.date);
        if (!slot.history) {
            t.monthHours -= slot.hours;
            if (slot.night) t.nightShifts--;
            if (slot.weekend) t.weekendShifts--;
        }
    }

    // ---- hard constraints --------------------------------------------------------------------------------------

    /** Null when the nurse may take the slot, otherwise a short reason. */
    private String violation(Context ctx, Timeline t, Slot s) {
        if (s.assigned.contains(t.id)) {
            return "already-assigned";
        }
        // a shift that ends the next morning also occupies that calendar day
        if (t.unavailable.contains(s.date) || t.unavailable.contains(s.end.toLocalDate())) {
            return "unavailable";
        }
        if (!s.requirements.isEmpty() && !qualified(t, s)) {
            return "qualification";
        }
        if (t.byDate.containsKey(s.date)) {
            return "same-day";
        }

        for (Slot a : t.slots) {
            double gap;
            if (!a.end.isAfter(s.start)) {
                gap = Duration.between(a.end, s.start).toMinutes() / 60.0;
            } else if (!s.end.isAfter(a.start)) {
                gap = Duration.between(s.end, a.start).toMinutes() / 60.0;
            } else {
                return "overlap";
            }
            if (gap < ctx.minRestHours) {
                return "rest";
            }
        }

        int run = 1;
        for (LocalDate d = s.date.minusDays(1); t.byDate.containsKey(d); d = d.minusDays(1)) run++;
        for (LocalDate d = s.date.plusDays(1); t.byDate.containsKey(d); d = d.plusDays(1)) run++;
        if (run > ctx.maxConsecutive) {
            return "consecutive-days";
        }

        int groupRun = 1;
        for (LocalDate d = s.date.minusDays(1); sameGroup(t.byDate.get(d), s); d = d.minusDays(1)) groupRun++;
        for (LocalDate d = s.date.plusDays(1); sameGroup(t.byDate.get(d), s); d = d.plusDays(1)) groupRun++;
        if (groupRun > (s.night ? ctx.maxNightRun : ctx.maxDayRun)) {
            return "consecutive-type";
        }

        int cap = t.nurse.getMaxHoursPerWeek();
        if (cap > 0) {
            double weekHours = s.hours;
            for (Slot a : t.slots) {
                if (a.weekKey == s.weekKey) {
                    weekHours += a.hours;
                }
            }
            if (weekHours > cap + ctx.overtime + 1e-9) {
                return "weekly-hours";
            }
        }
        return null;
    }

    private static boolean qualified(Timeline t, Slot s) {
        Set<String> has = new HashSet<>();
        if (t.nurse.getSpecializations() != null) {
            for (String spec : t.nurse.getSpecializations()) {
                if (spec != null) has.add(spec.trim().toLowerCase());
            }
        }
        return has.containsAll(s.requirements);
    }

    private static boolean sameGroup(Slot other, Slot s) {
        return other != null && other.night == s.night;
    }

    private List<Timeline> eligible(Context ctx, Slot s) {
        List<Timeline> result = new ArrayList<>();
        for (Timeline t : ctx.timelines) {
            if (!ctx.excluded.contains(t.id) && violation(ctx, t, s) == null) {
                result.add(t);
            }
        }
        return result;
    }

    // ---- choosing between eligible nurses -----------------------------------------------------------------------

    private double score(Context ctx, Timeline t, Slot s) {
        double score = FAIR_WEIGHT * t.monthHours / SHIFT_HOURS;
        if (s.night) score += NIGHT_WEIGHT * t.nightShifts;
        if (s.weekend) score += WEEKEND_WEIGHT * t.weekendShifts;

        List<String> preferred = t.nurse.getPreferredShifts();
        if (preferred != null && !preferred.isEmpty()) {
            score += preferred.contains(s.type.name()) ? -PREFERENCE_WEIGHT : PREFERENCE_WEIGHT;
        }
        Slot yesterday = t.byDate.get(s.date.minusDays(1));
        if (yesterday != null) {
            score += sameGroup(yesterday, s) ? -BLOCK_WEIGHT : BLOCK_WEIGHT / 2;
        }
        return score + ctx.rng.nextDouble() * JITTER;
    }

    private Timeline best(Context ctx, Slot s, List<Timeline> candidates) {
        Timeline best = null;
        double bestScore = Double.MAX_VALUE;
        for (Timeline t : candidates) {
            double score = score(ctx, t, s);
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    // ---- phases -----------------------------------------------------------------------------------------------

    /** Phase 1: most constrained slot first, so slots with few possible nurses are not starved by easy ones. */
    private void fillToMinimum(Context ctx) {
        Set<Slot> impossible = new HashSet<>();
        while (true) {
            Slot target = null;
            List<Timeline> targetCandidates = null;
            double lowestRatio = Double.MAX_VALUE;

            for (Slot s : ctx.slots) {
                int deficit = s.min - s.assigned.size();
                if (deficit <= 0 || impossible.contains(s)) {
                    continue;
                }
                List<Timeline> candidates = eligible(ctx, s);
                if (candidates.isEmpty()) {
                    impossible.add(s);   // constraints only tighten while filling, so it stays impossible
                    continue;
                }
                double ratio = (double) candidates.size() / deficit;
                if (ratio < lowestRatio) {
                    lowestRatio = ratio;
                    target = s;
                    targetCandidates = candidates;
                }
            }
            if (target == null) {
                return;
            }
            assign(best(ctx, target, targetCandidates), target);
        }
    }

    /** Phase 2: add one nurse at a time to the emptiest slots so no slot hogs the best candidates. */
    private void raiseTowardsMaximum(Context ctx) {
        boolean progress = true;
        while (progress) {
            progress = false;
            List<Slot> order = new ArrayList<>(ctx.slots);
            order.sort(Comparator.comparingInt((Slot s) -> s.assigned.size()));
            for (Slot s : order) {
                if (s.assigned.size() >= s.max) {
                    continue;
                }
                List<Timeline> candidates = eligible(ctx, s);
                if (!candidates.isEmpty()) {
                    assign(best(ctx, s, candidates), s);
                    progress = true;
                }
            }
        }
    }

    /** Phase 3: a slot still short may take a nurse away from a slot that has more than its minimum. */
    private void repairShortSlots(Context ctx) {
        for (Slot s : ctx.slots) {
            while (s.assigned.size() < s.min) {
                boolean repaired = false;
                for (Timeline t : ctx.timelines) {
                    if (ctx.excluded.contains(t.id) || s.assigned.contains(t.id)) {
                        continue;
                    }
                    String reason = violation(ctx, t, s);
                    if (reason == null) {
                        assign(t, s);
                        repaired = true;
                        break;
                    }
                    if ("unavailable".equals(reason)) {
                        continue;
                    }
                    for (Slot other : new ArrayList<>(t.slots)) {
                        if (other.history || other == s || other.fixed.contains(t.id)
                                || other.assigned.size() <= other.min) {
                            continue;
                        }
                        unassign(t, other);
                        if (violation(ctx, t, s) == null) {
                            assign(t, s);
                            repaired = true;
                            break;
                        }
                        assign(t, other);   // put it back
                    }
                    if (repaired) {
                        break;
                    }
                }
                if (!repaired) {
                    break;
                }
            }
        }
    }

    /** Phase 4: move shifts from the busiest nurses to the quietest while the gap is more than one shift. */
    private void balanceHours(Context ctx) {
        List<Timeline> candidates = new ArrayList<>();
        for (Timeline t : ctx.timelines) {
            if (!ctx.excluded.contains(t.id)) {
                candidates.add(t);
            }
        }
        for (int i = 0; i < BALANCE_ITERATIONS; i++) {
            List<Timeline> busiest = new ArrayList<>(candidates);
            busiest.sort((a, b) -> {
                int byHours = Double.compare(b.monthHours, a.monthHours);
                return byHours != 0 ? byHours : a.id.compareTo(b.id);
            });
            boolean moved = false;

            search:
            for (Timeline high : busiest) {
                for (int j = busiest.size() - 1; j >= 0; j--) {
                    Timeline low = busiest.get(j);
                    if (high.monthHours - low.monthHours <= SHIFT_HOURS) {
                        break;
                    }
                    for (Slot s : new ArrayList<>(high.slots)) {
                        if (s.history || s.fixed.contains(high.id) || s.assigned.contains(low.id)) {
                            continue;
                        }
                        unassign(high, s);
                        if (violation(ctx, low, s) == null) {
                            assign(low, s);
                            moved = true;
                            break search;
                        }
                        assign(high, s);
                    }
                }
            }
            if (!moved) {
                return;
            }
        }
    }
}
