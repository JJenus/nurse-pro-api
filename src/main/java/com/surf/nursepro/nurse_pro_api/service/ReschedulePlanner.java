package com.surf.nursepro.nurse_pro_api.service;

import com.surf.nursepro.nurse_pro_api.dto.RescheduleResult;
import com.surf.nursepro.nurse_pro_api.dto.ScheduleGenerationParams;
import com.surf.nursepro.nurse_pro_api.entity.Nurse;
import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.enums.RescheduleMode;
import com.surf.nursepro.nurse_pro_api.util.ShiftTimes;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * Works out what rescheduling a date range would change, without touching any entity. It prepares the locked and
 * history shifts, runs {@link ScheduleGenerator}, and compares the outcome with the current assignments.
 */
@Component
public class ReschedulePlanner {

    private final ScheduleGenerator generator;
    private final ScheduleValidator validator;

    public ReschedulePlanner(ScheduleGenerator generator, ScheduleValidator validator) {
        this.generator = generator;
        this.validator = validator;
    }

    public static class Input {
        private String scheduleId;
        private int year;
        private int month;
        private List<Nurse> nurses = new ArrayList<>();
        private Collection<Shift> scheduleShifts = new ArrayList<>();
        private Collection<Shift> neighbourShifts = new ArrayList<>();
        private LocalDate from;
        private LocalDate to;
        private RescheduleMode mode = RescheduleMode.FILL_GAPS;
        private Set<String> excludedNurseIds = new HashSet<>();
        private Set<String> lockedShiftIds = new HashSet<>();
        private ScheduleGenerationParams.Constraints constraints = new ScheduleGenerationParams.Constraints();
        private Long seed;

        public String getScheduleId() { return scheduleId; }
        public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }
        public int getYear() { return year; }
        public void setYear(int year) { this.year = year; }
        public int getMonth() { return month; }
        public void setMonth(int month) { this.month = month; }
        public List<Nurse> getNurses() { return nurses; }
        public void setNurses(List<Nurse> nurses) { this.nurses = nurses; }
        /** Every shift of the schedule being rescheduled. */
        public Collection<Shift> getScheduleShifts() { return scheduleShifts; }
        public void setScheduleShifts(Collection<Shift> scheduleShifts) { this.scheduleShifts = scheduleShifts; }
        /** Shifts of the neighbouring months that border the range; they only constrain new assignments. */
        public Collection<Shift> getNeighbourShifts() { return neighbourShifts; }
        public void setNeighbourShifts(Collection<Shift> neighbourShifts) { this.neighbourShifts = neighbourShifts; }
        public LocalDate getFrom() { return from; }
        public void setFrom(LocalDate from) { this.from = from; }
        public LocalDate getTo() { return to; }
        public void setTo(LocalDate to) { this.to = to; }
        public RescheduleMode getMode() { return mode; }
        public void setMode(RescheduleMode mode) { this.mode = mode; }
        public Set<String> getExcludedNurseIds() { return excludedNurseIds; }
        public void setExcludedNurseIds(Set<String> excludedNurseIds) { this.excludedNurseIds = excludedNurseIds; }
        public Set<String> getLockedShiftIds() { return lockedShiftIds; }
        public void setLockedShiftIds(Set<String> lockedShiftIds) { this.lockedShiftIds = lockedShiftIds; }
        public ScheduleGenerationParams.Constraints getConstraints() { return constraints; }
        public void setConstraints(ScheduleGenerationParams.Constraints constraints) { this.constraints = constraints; }
        public Long getSeed() { return seed; }
        public void setSeed(Long seed) { this.seed = seed; }
    }

    public static class Plan {
        private final RescheduleResult result;
        private final Map<String, Shift> newShiftsByKey;

        Plan(RescheduleResult result, Map<String, Shift> newShiftsByKey) {
            this.result = result;
            this.newShiftsByKey = newShiftsByKey;
        }

        public RescheduleResult getResult() { return result; }
        /** Unsaved shifts to create, keyed by {@link ScheduleGenerator#slotKey}. */
        public Map<String, Shift> getNewShiftsByKey() { return newShiftsByKey; }
    }

    public Plan plan(Input in) {
        Map<String, Nurse> nurseById = new HashMap<>();
        in.getNurses().forEach(n -> nurseById.put(n.getId(), n));
        Set<String> excluded = in.getExcludedNurseIds() != null ? in.getExcludedNurseIds() : new HashSet<>();
        Set<String> lockedIds = in.getLockedShiftIds() != null ? in.getLockedShiftIds() : new HashSet<>();

        // Shifts inside the range that belong to the generated department are rescheduled; everything else only
        // constrains the nurses (history).
        List<Shift> inRange = new ArrayList<>();
        List<Shift> history = new ArrayList<>(in.getNeighbourShifts());
        for (Shift shift : in.getScheduleShifts()) {
            boolean withinRange = shift.getDate() != null
                    && !shift.getDate().isBefore(in.getFrom()) && !shift.getDate().isAfter(in.getTo());
            boolean general = ScheduleGenerator.DEPARTMENT.equals(shift.getDepartment()) && shift.getType() != null;
            if (withinRange && general) {
                inRange.add(shift);
            } else {
                history.add(shift);
            }
        }

        Map<String, Shift> existingByKey = new LinkedHashMap<>();
        Map<String, Integer> requiredStaff = new HashMap<>();
        for (Shift shift : inRange) {
            String key = ScheduleGenerator.slotKey(shift.getDate(), shift.getType());
            existingByKey.put(key, shift);
            requiredStaff.put(key, shift.getRequiredStaff());
        }

        boolean rebuild = in.getMode() == RescheduleMode.REBUILD_RANGE;
        List<Shift> locked = new ArrayList<>();
        for (Shift shift : inRange) {
            if (!rebuild || lockedIds.contains(shift.getId())) {
                locked.add(copyWithAssignments(shift, stillValid(shift, nurseById, excluded)));
            }
        }

        ScheduleGenerator.Request request = new ScheduleGenerator.Request();
        request.setYear(in.getYear());
        request.setMonth(in.getMonth());
        request.setNurses(in.getNurses());
        request.setConstraints(in.getConstraints());
        request.setSeed(in.getSeed());
        request.setRangeStart(in.getFrom());
        request.setRangeEnd(in.getTo());
        request.setHistory(history);
        request.setLocked(locked);
        request.setExcludedNurseIds(excluded);
        request.setRequiredStaffBySlot(requiredStaff);
        request.setFillToMaximum(rebuild);
        if (!rebuild) {
            // gap filling never invents shifts; it only staffs the ones that exist
            request.setOnlySlotKeys(new HashSet<>(existingByKey.keySet()));
        }

        ScheduleGenerator.Result generated = generator.generate(request);

        RescheduleResult result = new RescheduleResult();
        result.setSeed(generated.getSeed());
        result.setFrom(in.getFrom());
        result.setTo(in.getTo());
        result.setUnfilledPositions(generated.getUnfilledPositions());

        Map<String, Shift> newShifts = new LinkedHashMap<>();
        Map<String, Set<String>> afterByShiftId = new HashMap<>();
        for (Shift proposed : generated.getShifts()) {
            String key = ScheduleGenerator.slotKey(proposed.getDate(), proposed.getType());
            Shift existing = existingByKey.get(key);
            Set<String> before = existing == null || existing.getAssignedNurses() == null
                    ? new LinkedHashSet<>() : new LinkedHashSet<>(existing.getAssignedNurses());
            Set<String> after = proposed.getAssignedNurses();

            List<String> added = new ArrayList<>();
            for (String id : after) {
                if (!before.contains(id)) added.add(id);
            }
            List<String> removed = new ArrayList<>();
            for (String id : before) {
                if (!after.contains(id)) removed.add(id);
            }

            if (existing == null) {
                newShifts.put(key, proposed);
                result.getChanges().add(new RescheduleResult.ShiftChange(
                        null, proposed.getDate(), proposed.getType(), true, added, removed));
                result.setShiftsCreated(result.getShiftsCreated() + 1);
            } else {
                afterByShiftId.put(existing.getId(), after);
                if (added.isEmpty() && removed.isEmpty()) {
                    continue;
                }
                result.getChanges().add(new RescheduleResult.ShiftChange(
                        existing.getId(), proposed.getDate(), proposed.getType(), false, added, removed));
                result.setShiftsChanged(result.getShiftsChanged() + 1);
            }
            result.setNursesAdded(result.getNursesAdded() + added.size());
            result.setNursesRemoved(result.getNursesRemoved() + removed.size());
        }

        // Conflicts of the schedule as it would be after the change
        List<Shift> proposedSchedule = new ArrayList<>();
        for (Shift shift : in.getScheduleShifts()) {
            Set<String> after = afterByShiftId.get(shift.getId());
            proposedSchedule.add(after == null ? shift : copyWithAssignments(shift, after));
        }
        int counter = 0;
        for (Shift created : newShifts.values()) {
            Shift copy = copyWithAssignments(created, created.getAssignedNurses());
            copy.setId("new-" + (counter++));
            proposedSchedule.add(copy);
        }
        result.setConflicts(validator.validate(in.getScheduleId(), proposedSchedule, nurseById));

        return new Plan(result, newShifts);
    }

    /** The shift's nurses minus excluded nurses, nurses who no longer exist, and nurses unavailable on its dates. */
    private Set<String> stillValid(Shift shift, Map<String, Nurse> nurseById, Set<String> excluded) {
        Set<String> kept = new LinkedHashSet<>();
        if (shift.getAssignedNurses() == null) {
            return kept;
        }
        boolean overnight = ShiftTimes.crossesMidnight(shift.getStartTime(), shift.getEndTime());
        for (String nurseId : shift.getAssignedNurses()) {
            Nurse nurse = nurseById.get(nurseId);
            if (nurse == null || excluded.contains(nurseId)) {
                continue;
            }
            List<LocalDate> unavailable = nurse.getUnavailableDates();
            if (unavailable != null && (unavailable.contains(shift.getDate())
                    || (overnight && unavailable.contains(shift.getDate().plusDays(1))))) {
                continue;
            }
            kept.add(nurseId);
        }
        return kept;
    }

    private Shift copyWithAssignments(Shift source, Set<String> assignments) {
        Shift copy = new Shift();
        copy.setId(source.getId());
        copy.setDate(source.getDate());
        copy.setStartTime(source.getStartTime());
        copy.setEndTime(source.getEndTime());
        copy.setType(source.getType());
        copy.setDepartment(source.getDepartment());
        copy.setRequiredStaff(source.getRequiredStaff());
        copy.setAssignedNurses(new LinkedHashSet<>(assignments));
        return copy;
    }
}
