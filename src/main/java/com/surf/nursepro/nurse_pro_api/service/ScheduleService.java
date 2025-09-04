package com.surf.nursepro.nurse_pro_api.service;

import com.itextpdf.text.Paragraph;
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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

// Excel imports
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFColor;
import java.io.ByteArrayOutputStream;


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

    @Transactional(readOnly = true)
    public ApiResponse<byte[]> exportSchedulesi(List<Integer> months, int year, String format) {
        if (months == null || months.isEmpty() || months.stream().anyMatch(m -> m < 1 || m > 12) || year < 2000) {
            logger.warn("Invalid export parameters: months={}, year={}", months, year);
            throw new IllegalArgumentException("Invalid months or year");
        }
        if (!format.equalsIgnoreCase("pdf") && !format.equalsIgnoreCase("excel")) {
            logger.warn("Invalid export format: {}", format);
            throw new IllegalArgumentException("Invalid format, must be 'pdf' or 'excel'");
        }

        List<Schedule> schedules = scheduleRepository.findByMonthInAndYear(months, year);
        if (schedules.isEmpty()) {
            logger.warn("No schedules found for months {} and year {}", months, year);
            throw new IllegalArgumentException("No schedules found for the specified months and year");
        }

        try {
            byte[] fileContent;
            String fileName;
            if (format.equalsIgnoreCase("pdf")) {
                fileContent = generatePdf(schedules);
                fileName = "schedules-" + year + "-" + months.stream().map(String::valueOf).collect(Collectors.joining("_")) + ".pdf";
            } else {
                fileContent = generateExcel(schedules);
                fileName = "schedules-" + year + "-" + months.stream().map(String::valueOf).collect(Collectors.joining("_")) + ".xlsx";
            }
            logger.info("Exported {} schedules for months {} and year {} as {}", schedules.size(), months, year, format);
            return new ApiResponse<>(fileContent, "Schedules exported successfully", true);
        } catch (Exception e) {
            logger.error("Error generating {} export: {}", format, e.getMessage());
            throw new IllegalArgumentException("Failed to generate export: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public ApiResponse<byte[]> exportSchedules(List<Integer> months, int year, String format) {
        if (months == null || months.isEmpty() || months.stream().anyMatch(m -> m < 1 || m > 12) || year < 2000) {
            logger.warn("Invalid export parameters: months={}, year={}", months, year);
            throw new IllegalArgumentException("Invalid months or year");
        }
        if (!format.equalsIgnoreCase("pdf") && !format.equalsIgnoreCase("excel")) {
            logger.warn("Invalid export format: {}", format);
            throw new IllegalArgumentException("Invalid format, must be 'pdf' or 'excel'");
        }

        List<Schedule> schedules = scheduleRepository.findByMonthInAndYear(months, year);
        if (schedules.isEmpty()) {
            logger.warn("No schedules found for months {} and year {}", months, year);
            throw new IllegalArgumentException("No schedules found for the specified months and year");
        }

        try {
            byte[] fileContent;
            String fileName;
            if (format.equalsIgnoreCase("pdf")) {
                fileContent = generatePdf(schedules);
                fileName = "schedules-" + year + "-" + months.stream().map(String::valueOf).collect(Collectors.joining("_")) + ".pdf";
            } else {
                fileContent = generateExcel(schedules);
                fileName = "schedules-" + year + "-" + months.stream().map(String::valueOf).collect(Collectors.joining("_")) + ".xlsx";
            }
            logger.info("Exported {} schedules for months {} and year {} as {}", schedules.size(), months, year, format);
            return new ApiResponse<>(fileContent, "Schedules exported successfully", true);
        } catch (Exception e) {
            logger.error("Error generating {} export: {}", format, e.getMessage());
            throw new IllegalArgumentException("Failed to generate export: " + e.getMessage());
        }
    }

    private byte[] generatePdf(List<Schedule> schedules) throws com.itextpdf.text.DocumentException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        com.itextpdf.text.Document document = new com.itextpdf.text.Document(com.itextpdf.text.PageSize.A4.rotate());
        com.itextpdf.text.pdf.PdfWriter.getInstance(document, baos);
        document.open();

        // Title
        com.itextpdf.text.Font titleFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 16, com.itextpdf.text.Font.BOLD);
        com.itextpdf.text.Paragraph title = new com.itextpdf.text.Paragraph("Nurse Schedule Export", titleFont);
        title.setAlignment(com.itextpdf.text.Element.ALIGN_CENTER);
        title.setSpacingAfter(20);
        document.add(title);

        // Shift times legend
        com.itextpdf.text.Font legendFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 10);
        com.itextpdf.text.Paragraph legend = new com.itextpdf.text.Paragraph(
                "Shift Times: Day (07:00–15:00) • Evening (15:00–23:00) • Night (23:00–07:00)",
                legendFont
        );
        legend.setAlignment(com.itextpdf.text.Element.ALIGN_CENTER);
        legend.setSpacingAfter(15);
        document.add(legend);

        for (Schedule schedule : schedules) {
            // Schedule header
            Paragraph scheduleHeader = getScheduleHeader(schedule);
            document.add(scheduleHeader);

            // Group shifts by week
            Map<LocalDate, List<Shift>> shiftsByDate = schedule.getShifts().stream()
                    .collect(Collectors.groupingBy(Shift::getDate));

            List<LocalDate> sortedDates = shiftsByDate.keySet().stream()
                    .sorted()
                    .toList();

            // Process by weeks
            int daysPerPage = 7; // One week per page/table
            for (int weekStart = 0; weekStart < sortedDates.size(); weekStart += daysPerPage) {
                int weekEnd = Math.min(weekStart + daysPerPage, sortedDates.size());
                List<LocalDate> weekDates = sortedDates.subList(weekStart, weekEnd);

                // Create table for the week: Shift Type + 7 days
                com.itextpdf.text.pdf.PdfPTable table = new com.itextpdf.text.pdf.PdfPTable(weekDates.size() + 1); // +1 for shift type column
                table.setWidthPercentage(100);
                table.setSpacingBefore(10);
                table.setSpacingAfter(20);

                // Table headers - Days of week
                com.itextpdf.text.pdf.PdfPCell shiftTypeHeader = new com.itextpdf.text.pdf.PdfPCell(
                        new com.itextpdf.text.Phrase("Shift", new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 10, com.itextpdf.text.Font.BOLD))
                );
                shiftTypeHeader.setBackgroundColor(new com.itextpdf.text.BaseColor(240, 240, 240));
                shiftTypeHeader.setPadding(5);
                table.addCell(shiftTypeHeader);

                for (LocalDate date : weekDates) {
                    com.itextpdf.text.pdf.PdfPCell dateHeader = new com.itextpdf.text.pdf.PdfPCell(
                            new com.itextpdf.text.Phrase(
                                    date.format(DateTimeFormatter.ofPattern("EEE d")),
                                    new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 10, com.itextpdf.text.Font.BOLD)
                            )
                    );
                    dateHeader.setBackgroundColor(new com.itextpdf.text.BaseColor(240, 240, 240));
                    dateHeader.setPadding(5);
                    table.addCell(dateHeader);
                }

                // Add rows for each shift type
                for (ShiftType shiftType : ShiftType.values()) {
                    // Shift type cell
                    com.itextpdf.text.pdf.PdfPCell shiftTypeCell = new com.itextpdf.text.pdf.PdfPCell(
                            new com.itextpdf.text.Phrase(shiftType.toString(), new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 10, com.itextpdf.text.Font.BOLD))
                    );
                    shiftTypeCell.setPadding(5);
                    switch (shiftType) {
                        case Day: shiftTypeCell.setBackgroundColor(new com.itextpdf.text.BaseColor(255, 255, 200)); break;
                        case Evening: shiftTypeCell.setBackgroundColor(new com.itextpdf.text.BaseColor(255, 220, 200)); break;
                        case Night: shiftTypeCell.setBackgroundColor(new com.itextpdf.text.BaseColor(200, 200, 255)); break;
                    }
                    table.addCell(shiftTypeCell);

                    // Shift cells for each day
                    for (LocalDate date : weekDates) {
                        List<Shift> dailyShifts = shiftsByDate.get(date);
                        Optional<Shift> shiftForType = dailyShifts.stream()
                                .filter(shift -> shift.getType() == shiftType)
                                .findFirst();

                        com.itextpdf.text.pdf.PdfPCell cell = new com.itextpdf.text.pdf.PdfPCell();
                        cell.setPadding(5);

                        if (shiftForType.isPresent()) {
                            Shift shift = shiftForType.get();

                            // Get nurse names
                            List<String> nurseNames = shift.getAssignedNurses().stream()
                                    .map(nurseId -> {
                                        Optional<Nurse> nurse = nurseRepository.findById(nurseId);
                                        return nurse.map(n -> n.getFirstName() + " " + n.getLastName()).orElse("Unknown");
                                    })
                                    .toList();

                            // Create content with line breaks
                            com.itextpdf.text.Phrase content = new com.itextpdf.text.Phrase();
                            content.add(new com.itextpdf.text.Chunk(shift.getDepartment() + "\n", new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 8)));
                            content.add(new com.itextpdf.text.Chunk(shift.getStartTime() + "–" + shift.getEndTime() + "\n", new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 7)));
//                            content.add(new com.itextpdf.text.Chunk(shift.getAssignedNurses().size() + "/" + shift.getRequiredStaff() + "\n", new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 8, com.itextpdf.text.Font.BOLD)));

                            // Add nurse names (max 2 per line to prevent overflow)
                            for (int i = 0; i < nurseNames.size(); i++) {
//                                if (i > 0 && i % 2 == 0) {
                                    content.add(new com.itextpdf.text.Chunk("\n"));
//                                }
                                content.add(new com.itextpdf.text.Chunk(nurseNames.get(i) + (i < nurseNames.size() - 1 ? ", " : ""),
                                        new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 8, com.itextpdf.text.Font.BOLD)));
                            }

                            cell.addElement(content);

                            // Highlight understaffed shifts
                            if (shift.getAssignedNurses().size() < shift.getRequiredStaff()) {
                                cell.setBackgroundColor(new com.itextpdf.text.BaseColor(255, 200, 200));
                            }
                        } else {
                            cell.addElement(new com.itextpdf.text.Phrase("No shift", new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 8)));
                        }

                        table.addCell(cell);
                    }
                }

                document.add(table);

                // Add page break if more weeks to process
                if (weekEnd < sortedDates.size()) {
                    document.newPage();
                }
            }
        }

        document.close();
        return baos.toByteArray();
    }

    private Paragraph getScheduleHeader(Schedule schedule) {
        com.itextpdf.text.Font headerFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 14, com.itextpdf.text.Font.BOLD);
        Paragraph scheduleHeader = new Paragraph(
                "Schedule for " + getMonthName(schedule.getMonth()) + " " + schedule.getYear(),
                headerFont
        );
        scheduleHeader.setAlignment(com.itextpdf.text.Element.ALIGN_CENTER);
        scheduleHeader.setSpacingAfter(15);
        return scheduleHeader;
    }

    private byte[] generateExcel(List<Schedule> schedules) {
        Workbook workbook = new XSSFWorkbook();

        // Use XSSFColor instead of Color
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dayStyle = createShiftStyle(workbook, new XSSFColor(new java.awt.Color(255, 255, 200), null)); // Yellow
        CellStyle eveningStyle = createShiftStyle(workbook, new XSSFColor(new java.awt.Color(255, 220, 200), null)); // Orange
        CellStyle nightStyle = createShiftStyle(workbook, new XSSFColor(new java.awt.Color(200, 200, 255), null)); // Blue
        CellStyle understaffedStyle = createUnderstaffedStyle(workbook);

        for (Schedule schedule : schedules) {
            Sheet sheet = workbook.createSheet(getMonthName(schedule.getMonth()) + " " + schedule.getYear());

            // Title row
            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Nurse Schedule - " + getMonthName(schedule.getMonth()) + " " + schedule.getYear());
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            titleStyle.setFont(titleFont);
            titleCell.setCellStyle(titleStyle);

            // Group shifts by date
            Map<LocalDate, List<Shift>> shiftsByDate = schedule.getShifts().stream()
                    .collect(Collectors.groupingBy(Shift::getDate));

            int rowNum = 2; // Start from row 2
            String[] headers = {"Shift Type", "Time", "Department", "Staffing", "Assigned Nurses"};

            for (LocalDate date : shiftsByDate.keySet().stream().sorted().collect(Collectors.toList())) {
                List<Shift> dailyShifts = shiftsByDate.get(date);

                // Date header
                Row dateRow = sheet.createRow(rowNum++);
                Cell dateCell = dateRow.createCell(0);
                dateCell.setCellValue(date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")));
                CellStyle dateStyle = workbook.createCellStyle();
                Font dateFont = workbook.createFont();
                dateFont.setBold(true);
                dateFont.setFontHeightInPoints((short) 12);
                dateStyle.setFont(dateFont);
                dateCell.setCellStyle(dateStyle);

                // Table headers
                Row headerRow = sheet.createRow(rowNum++);
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }

                // Shift rows
                for (Shift shift : dailyShifts.stream()
                        .sorted(Comparator.comparing(s -> s.getType().ordinal()))
                        .collect(Collectors.toList())) {

                    Row row = sheet.createRow(rowNum++);

                    // Shift Type
                    Cell typeCell = row.createCell(0);
                    typeCell.setCellValue(shift.getType().toString());
                    switch (shift.getType()) {
                        case Day: typeCell.setCellStyle(dayStyle); break;
                        case Evening: typeCell.setCellStyle(eveningStyle); break;
                        case Night: typeCell.setCellStyle(nightStyle); break;
                    }

                    // Time
                    row.createCell(1).setCellValue(shift.getStartTime() + " - " + shift.getEndTime());

                    // Department
                    row.createCell(2).setCellValue(shift.getDepartment());

                    // Staffing
                    Cell staffingCell = row.createCell(3);
                    staffingCell.setCellValue(shift.getAssignedNurses().size() + "/" + shift.getRequiredStaff());
                    if (shift.getAssignedNurses().size() < shift.getRequiredStaff()) {
                        staffingCell.setCellStyle(understaffedStyle);
                    }

                    // Assigned Nurses (full names)
                    List<String> nurseNames = shift.getAssignedNurses().stream()
                            .map(nurseId -> {
                                Optional<Nurse> nurse = nurseRepository.findById(nurseId);
                                return nurse.map(n -> n.getFirstName() + " " + n.getLastName()).orElse("Unknown");
                            })
                            .collect(Collectors.toList());
                    row.createCell(4).setCellValue(String.join(", ", nurseNames));
                }

                rowNum++; // Add empty row between dates
            }

            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            workbook.write(baos);
            workbook.close();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to generate Excel: " + e.getMessage());
        }
        return baos.toByteArray();
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createShiftStyle(Workbook workbook, XSSFColor color) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(color);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createUnderstaffedStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.RED.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private String getMonthName(int month) {
        String[] monthNames = {"January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"};
        return monthNames[month - 1];
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