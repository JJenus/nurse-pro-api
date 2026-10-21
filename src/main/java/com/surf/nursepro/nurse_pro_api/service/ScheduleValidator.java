package com.surf.nursepro.nurse_pro_api.service;

import com.surf.nursepro.nurse_pro_api.dto.ScheduleGenerationParams;
import com.surf.nursepro.nurse_pro_api.entity.Nurse;
import com.surf.nursepro.nurse_pro_api.entity.ScheduleConflict;
import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.enums.ScheduleConflictSeverity;
import com.surf.nursepro.nurse_pro_api.enums.ScheduleConflictType;
import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import com.surf.nursepro.nurse_pro_api.util.ShiftTimes;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * The single place that decides whether a set of shifts is acceptable. It is a pure function of its
 * inputs (no repositories, nothing persisted) so generation, manual edits, previews, swaps and
 * rescheduling can all share the same rules. New rules (min rest hours, consecutive days, weekly
 * hours cap) are added here in the next phases.
 */
@Component
public class ScheduleValidator {

    /** Placeholder id used for a shift that has not been saved yet. */
    public static final String CANDIDATE_ID = "__candidate__";

    public List<ScheduleConflict> validate(String scheduleId, Collection<Shift> shifts, Map<String, Nurse> nurses) {
        return validate(scheduleId, shifts, nurses, new ScheduleGenerationParams.Constraints());
    }

    /** Same as above, checked against the given rules (rest hours, consecutive days, weekly hours). */
    public List<ScheduleConflict> validate(String scheduleId, Collection<Shift> shifts, Map<String, Nurse> nurses,
                                           ScheduleGenerationParams.Constraints rules) {
        List<ScheduleConflict> conflicts = new ArrayList<>();

        // nurse -> shifts per start date, used to spot a nurse booked twice on the same day
        Map<String, Map<LocalDate, List<Shift>>> byNurseAndDay = new HashMap<>();
        for (Shift shift : shifts) {
            for (String nurseId : assigned(shift)) {
                byNurseAndDay
                        .computeIfAbsent(nurseId, k -> new HashMap<>())
                        .computeIfAbsent(shift.getDate(), k -> new ArrayList<>())
                        .add(shift);
            }
        }

        for (Shift shift : shifts) {
            checkStaffing(scheduleId, shift, conflicts);

            for (String nurseId : assigned(shift)) {
                Nurse nurse = nurses.get(nurseId);
                if (nurse == null) {
                    conflicts.add(conflict(scheduleId, shift, nurseId, ScheduleConflictType.availability,
                            ScheduleConflictSeverity.high,
                            String.format("%s %s shift has an assigned nurse that no longer exists",
                                    shift.getDepartment(), shift.getType()),
                            "Remove the nurse from this shift", "Assign an existing nurse"));
                    continue;
                }

                checkAvailability(scheduleId, shift, nurse, conflicts);

                List<Shift> sameDay = byNurseAndDay.get(nurseId).get(shift.getDate());
                if (sameDay != null && sameDay.size() > 1) {
                    conflicts.add(conflict(scheduleId, shift, nurseId, ScheduleConflictType.overlap,
                            ScheduleConflictSeverity.critical,
                            String.format("%s is assigned to %d shifts on %s",
                                    name(nurse), sameDay.size(), shift.getDate()),
                            "Keep the nurse on only one shift per day"));
                }
            }
        }
        checkWorkPatterns(scheduleId, shifts, nurses, rules != null ? rules : new ScheduleGenerationParams.Constraints(), conflicts);
        return conflicts;
    }

    /**
     * Validates a shift that is not saved yet (or a proposed change to a saved one) against the other shifts
     * of its schedule. Only conflicts involving the candidate are returned, with shiftId set to
     * {@code realShiftId} (null for a new shift).
     */
    public List<ScheduleConflict> validateCandidate(String scheduleId, Shift candidate, String realShiftId,
                                                    Collection<Shift> otherShifts, Map<String, Nurse> nurses,
                                                    ScheduleGenerationParams.Constraints rules) {
        candidate.setId(CANDIDATE_ID);
        List<Shift> all = new ArrayList<>(otherShifts);
        all.add(candidate);

        List<ScheduleConflict> result = new ArrayList<>();
        for (ScheduleConflict c : validate(scheduleId, all, nurses, rules)) {
            if (CANDIDATE_ID.equals(c.getShiftId())) {
                c.setShiftId(realShiftId);
                result.add(c);
            }
        }
        return result;
    }

    /** Rest between shifts, consecutive working days and weekly hours, per nurse. */
    private void checkWorkPatterns(String scheduleId, Collection<Shift> shifts, Map<String, Nurse> nurses,
                                   ScheduleGenerationParams.Constraints rules, List<ScheduleConflict> out) {
        Map<String, List<Shift>> byNurse = new LinkedHashMap<>();
        for (Shift shift : shifts) {
            if (shift.getDate() == null) {
                continue;
            }
            for (String nurseId : assigned(shift)) {
                if (nurses.containsKey(nurseId)) {
                    byNurse.computeIfAbsent(nurseId, k -> new ArrayList<>()).add(shift);
                }
            }
        }

        for (Map.Entry<String, List<Shift>> entry : byNurse.entrySet()) {
            Nurse nurse = nurses.get(entry.getKey());
            List<Shift> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(Comparator.comparing(ShiftTimes::startOf));

            // 1. rest between consecutive shifts (two shifts on the same day are reported as an overlap instead)
            Shift previous = null;
            for (Shift shift : sorted) {
                if (previous != null && !previous.getDate().equals(shift.getDate())) {
                    LocalDateTime previousEnd = ShiftTimes.endOf(previous);
                    double gap = Duration.between(previousEnd, ShiftTimes.startOf(shift)).toMinutes() / 60.0;
                    if (gap < rules.getMinRestHours()) {
                        out.add(conflict(scheduleId, shift, nurse.getId(), ScheduleConflictType.rest,
                                ScheduleConflictSeverity.high,
                                String.format("%s has only %s of rest before this shift (minimum %dh)",
                                        name(nurse), formatHours(gap), rules.getMinRestHours()),
                                "Assign another nurse", "Move the shift to a later type"));
                    }
                }
                previous = shift;
            }

            // 2. consecutive working days, and consecutive day-type / night-type shifts
            Map<LocalDate, Shift> byDate = new TreeMap<>();
            for (Shift shift : sorted) {
                byDate.putIfAbsent(shift.getDate(), shift);
            }
            LocalDate lastDate = null;
            int run = 0;
            int groupRun = 0;
            boolean lastNight = false;
            for (Map.Entry<LocalDate, Shift> day : byDate.entrySet()) {
                boolean night = day.getValue().getType() == ShiftType.Night;
                boolean consecutive = lastDate != null && lastDate.plusDays(1).equals(day.getKey());
                run = consecutive ? run + 1 : 1;
                groupRun = consecutive && night == lastNight ? groupRun + 1 : 1;
                lastDate = day.getKey();
                lastNight = night;

                int groupLimit = night ? rules.getMaxConsecutiveNight() : rules.getMaxConsecutiveDay();
                // reported once per run, on the first shift that goes over the limit
                if (run == rules.getMaxConsecutiveShifts() + 1) {
                    out.add(conflict(scheduleId, day.getValue(), nurse.getId(), ScheduleConflictType.consecutive,
                            ScheduleConflictSeverity.medium,
                            String.format("%s works more than %d days in a row", name(nurse), rules.getMaxConsecutiveShifts()),
                            "Give the nurse a day off"));
                } else if (groupRun == groupLimit + 1) {
                    out.add(conflict(scheduleId, day.getValue(), nurse.getId(), ScheduleConflictType.consecutive,
                            ScheduleConflictSeverity.medium,
                            String.format("%s works more than %d %s shifts in a row", name(nurse), groupLimit,
                                    night ? "night" : "day"),
                            "Rotate the nurse to a different shift type"));
                }
            }

            // 3. weekly hours (ISO weeks, attributed to the shift's start date)
            int cap = nurse.getMaxHoursPerWeek();
            if (cap > 0) {
                Map<Integer, Double> weekHours = new LinkedHashMap<>();
                Map<Integer, Shift> weekLast = new HashMap<>();
                for (Shift shift : sorted) {
                    int week = shift.getDate().get(IsoFields.WEEK_BASED_YEAR) * 100
                            + shift.getDate().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                    weekHours.merge(week, ShiftTimes.hours(shift), Double::sum);
                    weekLast.put(week, shift);
                }
                double limit = cap + rules.getMaxOvertimeHours();
                for (Map.Entry<Integer, Double> week : weekHours.entrySet()) {
                    if (week.getValue() > limit + 1e-9) {
                        out.add(conflict(scheduleId, weekLast.get(week.getKey()), nurse.getId(),
                                ScheduleConflictType.overtime, ScheduleConflictSeverity.medium,
                                String.format("%s is scheduled for %s in one week (limit %s)",
                                        name(nurse), formatHours(week.getValue()), formatHours(limit)),
                                "Move a shift to another nurse"));
                    }
                }
            }
        }
    }

    private String formatHours(double hours) {
        return hours == Math.rint(hours) ? ((long) hours) + "h" : String.format("%.1fh", hours);
    }

    private void checkStaffing(String scheduleId, Shift shift, List<ScheduleConflict> out) {
        int assignedCount = assigned(shift).size();
        if (assignedCount < shift.getRequiredStaff()) {
            out.add(conflict(scheduleId, shift, null, ScheduleConflictType.understaffed,
                    assignedCount == 0 ? ScheduleConflictSeverity.critical : ScheduleConflictSeverity.high,
                    String.format("%s %s shift needs %d more nurse(s)", shift.getDepartment(), shift.getType(),
                            shift.getRequiredStaff() - assignedCount),
                    "Check for available nurses with matching qualifications",
                    "Consider overtime assignments",
                    "Review shift requirements"));
        }
    }

    private void checkAvailability(String scheduleId, Shift shift, Nurse nurse, List<ScheduleConflict> out) {
        if (nurse.getUnavailableDates() == null || nurse.getUnavailableDates().isEmpty() || shift.getDate() == null) {
            return;
        }
        // A night shift that crosses midnight also occupies the next calendar day.
        List<LocalDate> covered = new ArrayList<>();
        covered.add(shift.getDate());
        if (ShiftTimes.crossesMidnight(shift.getStartTime(), shift.getEndTime())) {
            covered.add(shift.getDate().plusDays(1));
        }
        for (LocalDate day : covered) {
            if (nurse.getUnavailableDates().contains(day)) {
                out.add(conflict(scheduleId, shift, nurse.getId(), ScheduleConflictType.availability,
                        ScheduleConflictSeverity.high,
                        String.format("%s is unavailable on %s", name(nurse), day),
                        "Assign another nurse", "Ask the nurse to update their availability"));
                return;
            }
        }
    }

    private ScheduleConflict conflict(String scheduleId, Shift shift, String nurseId, ScheduleConflictType type,
                                      ScheduleConflictSeverity severity, String message, String... suggestions) {
        ScheduleConflict c = new ScheduleConflict();
        c.setScheduleId(scheduleId);
        c.setShiftId(shift.getId());
        c.setNurseId(nurseId);
        c.setDate(shift.getDate());
        c.setType(type);
        c.setSeverity(severity);
        c.setMessage(message);
        c.setSuggestions(new ArrayList<>(Arrays.asList(suggestions)));
        c.setResolved(false);
        return c;
    }

    private Set<String> assigned(Shift shift) {
        return shift.getAssignedNurses() != null ? shift.getAssignedNurses() : Collections.emptySet();
    }

    private String name(Nurse nurse) {
        String full = ((nurse.getFirstName() != null ? nurse.getFirstName() : "") + " "
                + (nurse.getLastName() != null ? nurse.getLastName() : "")).trim();
        return full.isEmpty() ? nurse.getId() : full;
    }
}
