package com.surf.nursepro.nurse_pro_api.service;

import com.surf.nursepro.nurse_pro_api.dto.ApiResponse;
import com.surf.nursepro.nurse_pro_api.dto.ScheduleGenerationParams;
import com.surf.nursepro.nurse_pro_api.entity.*;
import com.surf.nursepro.nurse_pro_api.enums.*;
import com.surf.nursepro.nurse_pro_api.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScheduleService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);

    private final ScheduleRepository scheduleRepository;
    private final ShiftRepository shiftRepository;
    private final NurseRepository nurseRepository;
    private final SwapRequestRepository swapRequestRepository;
    private final WorkloadDataRepository workloadDataRepository;
    private final ScheduleConflictRepository conflictRepository;

    private static final int MAX_CONSECUTIVE_DAY = 3;
    private static final int MAX_CONSECUTIVE_NIGHT = 3;
    private static final int MIN_NURSES_PER_SHIFT = 2;
    private static final int MAX_NURSES_PER_SHIFT = 3;
    private static final int TARGET_SHIFTS_PER_NURSE = 14;

    public ApiResponse<List<Schedule>> fetchSchedules() {
        List<Schedule> schedules = scheduleRepository.findAll();
        logger.debug("Fetched {} schedules", schedules.size());
        return new ApiResponse<>(schedules, "Schedules fetched successfully", true);
    }

    @Transactional
    public ApiResponse<Schedule> generateSchedule(ScheduleGenerationParams params) {
        if (params == null || params.getMonth() < 1 || params.getMonth() > 12 || params.getYear() < 2000) {
            logger.warn("Invalid schedule generation parameters: {}", params);
            throw new IllegalArgumentException("Invalid month or year");
        }

        if (scheduleRepository.existsByMonthAndYear(params.getMonth(), params.getYear())) {
            logger.warn("Schedule already exists for month {} and year {}", params.getMonth(), params.getYear());
            throw new IllegalArgumentException("Schedule already exists for month " + params.getMonth() + " and year " + params.getYear());
        }

        List<Nurse> nurses = nurseRepository.findAll();
        if (nurses.isEmpty()) {
            logger.error("No nurses available for scheduling");
            throw new IllegalArgumentException("No nurses available for scheduling");
        }

        Map<String, NurseState> states = new HashMap<>();
        nurses.forEach(nurse -> states.put(nurse.getId(), new NurseState()));

        Schedule schedule = new Schedule();
        schedule.setMonth(params.getMonth());
        schedule.setYear(params.getYear());
        schedule.setGeneratedAt(LocalDateTime.now());
        schedule.setStatus(ScheduleStatus.Draft);
        List<Shift> shifts = new ArrayList<>();

        LocalDate startDate = LocalDate.of(params.getYear(), params.getMonth(), 1);
        int daysInMonth = startDate.getMonth().length(startDate.isLeapYear());

        for (int day = 0; day < daysInMonth; day++) {
            LocalDate currentDate = startDate.plusDays(day);
            Set<String> assignedToday = new HashSet<>();
            List<ShiftType> shiftTypes = Arrays.asList(ShiftType.values());
            Collections.shuffle(shiftTypes);

            for (ShiftType shiftType : shiftTypes) {
                Shift shift = createShift(currentDate, shiftType);
                List<Nurse> eligibleNurses = findEligibleNurses(nurses, states, assignedToday, shiftType, currentDate);

                int numToAssign = Math.min(
                        Math.max(MIN_NURSES_PER_SHIFT, eligibleNurses.size()),
                        MAX_NURSES_PER_SHIFT
                );

                if (eligibleNurses.size() >= MAX_NURSES_PER_SHIFT && Math.random() > 0.7) {
                    numToAssign = MAX_NURSES_PER_SHIFT;
                }

                if (eligibleNurses.isEmpty()) {
                    logger.warn("No eligible nurses for shift: {} on {}", shiftType, currentDate);
                    shift.setAssignedNurses(new ArrayList<>());
                    shifts.add(shift);
                    continue;
                }

                Collections.shuffle(eligibleNurses);
                List<String> assignedNurseIds = eligibleNurses.stream()
                        .limit(numToAssign)
                        .map(Nurse::getId)
                        .collect(Collectors.toList());

                shift.setAssignedNurses(assignedNurseIds);
                shifts.add(shift);

                updateNurseStates(states, assignedNurseIds, shiftType, day, currentDate);
                assignedToday.addAll(assignedNurseIds);
            }

            updateRestStates(states, assignedToday, day);
        }

        schedule.setShifts(shifts);
        Schedule savedSchedule = scheduleRepository.save(schedule);
        updateWorkloadData(savedSchedule);
        detectConflicts(savedSchedule);

        logger.info("Generated schedule ID: {} for {}/{}", savedSchedule.getId(), params.getMonth(), params.getYear());
        return new ApiResponse<>(savedSchedule, "Schedule generated successfully", true);
    }

    private Shift createShift(LocalDate date, ShiftType type) {
        Shift shift = new Shift();
        shift.setDate(date);
        shift.setType(type);
        shift.setDepartment("General");
        shift.setRequiredStaff(MIN_NURSES_PER_SHIFT);

        switch (type) {
            case Day:
                shift.setStartTime("07:00");
                shift.setEndTime("15:00");
                break;
            case Evening:
                shift.setStartTime("15:00");
                shift.setEndTime("23:00");
                break;
            case Night:
                shift.setStartTime("23:00");
                shift.setEndTime("07:00");
                break;
        }

        return shift;
    }

    private List<Nurse> findEligibleNurses(List<Nurse> nurses, Map<String, NurseState> states,
                                           Set<String> assignedToday, ShiftType shiftType,
                                           LocalDate currentDate) {
        String currentType = shiftType == ShiftType.Night ? "night" : "day";
        List<Nurse> eligible = new ArrayList<>();

        for (Nurse nurse : nurses) {
            if (assignedToday.contains(nurse.getId()) ||
                    nurse.getUnavailableDates().contains(currentDate)) {
                continue;
            }

            NurseState state = states.get(nurse.getId());
            if (state.restLeft > 0 || (state.afterNightRest && shiftType != ShiftType.Day)) {
                continue;
            }

            boolean canAssign = true;
            if (state.lastWorkDay == currentDate.getDayOfMonth() - 1) {
                if (state.lastType != null && state.lastType.equals(currentType)) {
                    int maxConsecutive = currentType.equals("day") ? MAX_CONSECUTIVE_DAY : MAX_CONSECUTIVE_NIGHT;
                    if (state.consecutive + 1 > maxConsecutive) {
                        canAssign = false;
                    }
                } else if (state.lastType != null) {
                    int prevK = state.consecutive;
                    int requiredOff = state.lastType.equals("day") ?
                            offDaysForDay(prevK) : offDaysForNight(prevK);
                    if (requiredOff > 0) {
                        canAssign = false;
                    }
                }
            }

            if (canAssign) {
                eligible.add(nurse);
            }
        }

        eligible.sort((a, b) -> {
            NurseState stateA = states.get(a.getId());
            NurseState stateB = states.get(b.getId());
            return Double.compare(
                    stateA.workDays + Math.random(),
                    stateB.workDays + Math.random()
            );
        });

        logger.debug("Found {} eligible nurses for {} shift on {}", eligible.size(), shiftType, currentDate);
        return eligible;
    }

    private void updateNurseStates(Map<String, NurseState> states, List<String> assignedNurseIds,
                                   ShiftType shiftType, int day, LocalDate currentDate) {
        String currentType = shiftType == ShiftType.Night ? "night" : "day";

        for (String nurseId : assignedNurseIds) {
            NurseState state = states.get(nurseId);
            state.workDays++;
            state.assignments[day] = shiftType.toString();

            if (day > 0 && state.lastWorkDay == day - 1 && state.lastType != null && state.lastType.equals(currentType)) {
                state.consecutive++;
            } else {
                state.consecutive = 1;
                state.lastType = currentType;
            }
            state.lastWorkDay = day;
            state.afterNightRest = false;
        }
    }

    private void updateRestStates(Map<String, NurseState> states, Set<String> assignedToday, int day) {
        states.forEach((nurseId, state) -> {
            if (!assignedToday.contains(nurseId) && state.lastWorkDay == day - 1 && state.lastType != null) {
                int k = state.consecutive;
                String t = state.lastType;
                int off = t.equals("day") ? offDaysForDay(k) : offDaysForNight(k);
                state.restLeft = off;
                if (t.equals("night")) {
                    state.afterNightRest = true;
                }
                logger.debug("Nurse {} assigned {} rest days after {} {} shifts", nurseId, off, k, t);
            }
            if (state.restLeft > 0) {
                state.restLeft--;
            }
        });
    }

    private int offDaysForDay(int k) {
        return k < 2 ? 0 : k - 1;
    }

    private int offDaysForNight(int k) {
        return k < 2 ? 0 : k - 1;
    }

    private void updateWorkloadData(Schedule schedule) {
        Map<String, WorkloadData> workloadMap = new HashMap<>();

        for (Shift shift : schedule.getShifts()) {
            boolean isWeekend = shift.getDate().getDayOfWeek().getValue() >= 6;
            boolean isNight = shift.getType() == ShiftType.Night;

            for (String nurseId : shift.getAssignedNurses()) {
                WorkloadData data = workloadMap.computeIfAbsent(nurseId, id -> {
                    WorkloadData wd = new WorkloadData();
                    wd.setNurseId(id);
                    wd.setMonth(schedule.getMonth());
                    wd.setYear(schedule.getYear());
                    wd.setCreatedAt(LocalDateTime.now());
                    return wd;
                });

                data.setShiftsCount(data.getShiftsCount() + 1);
                data.setTotalHours(data.getTotalHours() + 8); // Assuming 8-hour shifts
                if (isNight) {
                    data.setNightShifts(data.getNightShifts() + 1);
                }
                if (isWeekend) {
                    data.setWeekendShifts(data.getWeekendShifts() + 1);
                }
                data.setUpdatedAt(LocalDateTime.now());
            }
        }

        workloadDataRepository.saveAll(workloadMap.values());
        logger.debug("Updated workload data for {} nurses", workloadMap.size());
    }

    private void detectConflicts(Schedule schedule) {
        List<ScheduleConflict> conflicts = new ArrayList<>();

        for (Shift shift : schedule.getShifts()) {
            if (shift.getAssignedNurses().size() < shift.getRequiredStaff()) {
                ScheduleConflict conflict = new ScheduleConflict();
                conflict.setType(ScheduleConflictType.understaffed);
                conflict.setSeverity(shift.getAssignedNurses().isEmpty() ?
                        ScheduleConflictSeverity.critical : ScheduleConflictSeverity.high);
                conflict.setShiftId(shift.getId());
                conflict.setMessage(String.format("%s %s shift needs %d more nurse(s)",
                        shift.getDepartment(), shift.getType(),
                        shift.getRequiredStaff() - shift.getAssignedNurses().size()));
                conflict.setSuggestions(Arrays.asList(
                        "Check for available nurses with matching qualifications",
                        "Consider overtime assignments",
                        "Review shift requirements"
                ));
                conflicts.add(conflict);
            }
        }

        conflictRepository.saveAll(conflicts);
        logger.debug("Detected {} conflicts for schedule {}", conflicts.size(), schedule.getId());
    }

    @Transactional
    public ApiResponse<Shift> createShift(Shift shift) {
        if (shift == null || shift.getDate() == null || shift.getType() == null) {
            logger.warn("Invalid shift data: {}", shift);
            throw new IllegalArgumentException("Invalid shift data");
        }
        Shift savedShift = shiftRepository.save(shift);
        logger.info("Created shift ID: {}", savedShift.getId());
        return new ApiResponse<>(savedShift, "Shift created successfully", true);
    }

    @Transactional
    public ApiResponse<Shift> updateShift(String shiftId, Shift updatedShift) {
        if (shiftId == null || updatedShift == null) {
            logger.warn("Invalid shift ID or data: shiftId={}, updatedShift={}", shiftId, updatedShift);
            throw new IllegalArgumentException("Invalid shift ID or data");
        }
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> {
                    logger.error("Shift not found: {}", shiftId);
                    return new IllegalArgumentException("Shift not found");
                });
        shift.setDate(updatedShift.getDate());
        shift.setStartTime(updatedShift.getStartTime());
        shift.setEndTime(updatedShift.getEndTime());
        shift.setType(updatedShift.getType());
        shift.setDepartment(updatedShift.getDepartment());
        shift.setRequiredStaff(updatedShift.getRequiredStaff());
        shift.setAssignedNurses(updatedShift.getAssignedNurses());
        shift.setRequirements(updatedShift.getRequirements());
        Shift savedShift = shiftRepository.save(shift);
        logger.info("Updated shift ID: {}", savedShift.getId());
        return new ApiResponse<>(savedShift, "Shift updated successfully", true);
    }

    @Transactional
    public ApiResponse<Void> deleteShift(String shiftId) {
        if (shiftId == null) {
            logger.warn("Invalid shift ID: {}", shiftId);
            throw new IllegalArgumentException("Invalid shift ID");
        }
        if (!shiftRepository.existsById(shiftId)) {
            logger.error("Shift not found: {}", shiftId);
            throw new IllegalArgumentException("Shift not found");
        }
        shiftRepository.deleteById(shiftId);
        logger.info("Deleted shift ID: {}", shiftId);
        return new ApiResponse<>(null, "Shift deleted successfully", true);
    }

    @Transactional
    public ApiResponse<SwapRequest> createSwapRequest(SwapRequest request) {
        if (request == null || request.getShiftId() == null || request.getRequesterId() == null) {
            logger.warn("Invalid swap request data: {}", request);
            throw new IllegalArgumentException("Invalid swap request data");
        }
        request.setCreatedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());
        request.setStatus(SwapRequestStatus.Pending);
        SwapRequest savedRequest = swapRequestRepository.save(request);
        logger.info("Created swap request ID: {}", savedRequest.getId());
        return new ApiResponse<>(savedRequest, "Swap request created successfully", true);
    }

    @Transactional
    public ApiResponse<SwapRequest> approveSwapRequest(String requestId) {
        if (requestId == null) {
            logger.warn("Invalid swap request ID: {}", requestId);
            throw new IllegalArgumentException("Invalid swap request ID");
        }
        SwapRequest request = swapRequestRepository.findById(requestId)
                .orElseThrow(() -> {
                    logger.error("Swap request not found: {}", requestId);
                    return new IllegalArgumentException("Swap request not found");
                });
        request.setStatus(SwapRequestStatus.Approved);
        request.setUpdatedAt(LocalDateTime.now());
        request.setReviewedBy("Admin");
        SwapRequest savedRequest = swapRequestRepository.save(request);
        logger.info("Approved swap request ID: {}", savedRequest.getId());
        return new ApiResponse<>(savedRequest, "Swap request approved", true);
    }

    @Transactional
    public ApiResponse<SwapRequest> rejectSwapRequest(String requestId) {
        if (requestId == null) {
            logger.warn("Invalid swap request ID: {}", requestId);
            throw new IllegalArgumentException("Invalid swap request ID");
        }
        SwapRequest request = swapRequestRepository.findById(requestId)
                .orElseThrow(() -> {
                    logger.error("Swap request not found: {}", requestId);
                    return new IllegalArgumentException("Swap request not found");
                });
        request.setStatus(SwapRequestStatus.Rejected);
        request.setUpdatedAt(LocalDateTime.now());
        request.setReviewedBy("Admin");
        SwapRequest savedRequest = swapRequestRepository.save(request);
        logger.info("Rejected swap request ID: {}", savedRequest.getId());
        return new ApiResponse<>(savedRequest, "Swap request rejected", true);
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<WorkloadData>> getWorkloadData(String nurseId, int month, int year) {
        if (nurseId == null || month < 1 || month > 12 || year < 2000) {
            logger.warn("Invalid workload data parameters: nurseId={}, month={}, year={}", nurseId, month, year);
            throw new IllegalArgumentException("Invalid nurse ID, month, or year");
        }
        List<WorkloadData> workloadData = workloadDataRepository.findByNurseIdAndMonthAndYear(nurseId, month, year);
        logger.debug("Retrieved {} workload data entries for nurse {}", workloadData.size(), nurseId);
        return new ApiResponse<>(workloadData, "Workload data retrieved successfully", true);
    }

    private static class NurseState {
        int lastWorkDay = -1;
        int consecutive = 0;
        String lastType = null;
        int restLeft = 0;
        boolean afterNightRest = false;
        int workDays = 0;
        String[] assignments = new String[31];
    }
}