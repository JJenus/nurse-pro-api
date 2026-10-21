package com.surf.nursepro.nurse_pro_api.service;

import com.itextpdf.text.Paragraph;
import com.surf.nursepro.nurse_pro_api.dto.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surf.nursepro.nurse_pro_api.dto.MoveNurseRequest;
import com.surf.nursepro.nurse_pro_api.dto.RescheduleRequest;
import com.surf.nursepro.nurse_pro_api.dto.RescheduleResult;
import com.surf.nursepro.nurse_pro_api.dto.SwapRequestCreate;
import com.surf.nursepro.nurse_pro_api.dto.ScheduleGenerationParams;
import com.surf.nursepro.nurse_pro_api.dto.ScheduleGenerationResult;
import com.surf.nursepro.nurse_pro_api.dto.ShiftRequest;
import com.surf.nursepro.nurse_pro_api.exception.ConflictException;
import com.surf.nursepro.nurse_pro_api.util.ShiftTimes;
import com.surf.nursepro.nurse_pro_api.entity.*;
import com.surf.nursepro.nurse_pro_api.enums.*;
import com.surf.nursepro.nurse_pro_api.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final ScheduleValidator scheduleValidator;
    private final SwapPlanner swapPlanner;
    private final ScheduleGenerator scheduleGenerator;
    private final ReschedulePlanner reschedulePlanner;
    private final ScheduleChangeRepository scheduleChangeRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    /** Timezone used to decide what "today" is (past shifts cannot be swapped). */
    @Value("${app.timezone:Africa/Lagos}")
    private String appTimezone = "Africa/Lagos";

    private static final String IDEMPOTENCY_SCOPE_GENERATE = "schedule-generate";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;


    @Transactional(readOnly = true)
    public ApiResponse<List<Schedule>> fetchSchedules(Integer month, Integer year, ScheduleStatus status) {
        if (month != null && (month < 1 || month > 12)) {
            throw new IllegalArgumentException("month must be between 1 and 12");
        }
        List<Schedule> schedules = scheduleRepository.search(year, month, status);
        logger.debug("Fetched {} schedules", schedules.size());
        return new ApiResponse<>(schedules, "Schedules fetched successfully", true);
    }

    @Transactional(readOnly = true)
    public ApiResponse<Schedule> getSchedule(String scheduleId) {
        Schedule schedule = findSchedule(scheduleId);
        // touch the lazy collection inside the transaction so the response is fully populated
        schedule.getShifts().size();
        return new ApiResponse<>(schedule, "Schedule fetched successfully", true);
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<ScheduleConflict>> getConflicts(String scheduleId) {
        findSchedule(scheduleId);
        return new ApiResponse<>(conflictRepository.findByScheduleId(scheduleId), "Conflicts fetched successfully", true);
    }

    /** Recomputes conflicts and workload for a schedule from its current shifts. Safe to call repeatedly. */
    @Transactional
    public ApiResponse<List<ScheduleConflict>> validateSchedule(String scheduleId) {
        Schedule schedule = findSchedule(scheduleId);
        updateWorkloadData(schedule);
        List<ScheduleConflict> conflicts = detectConflicts(schedule);
        return new ApiResponse<>(conflicts, conflicts.isEmpty() ? "No conflicts found"
                : conflicts.size() + " conflict(s) found", true);
    }

    @Transactional
    public ApiResponse<Schedule> updateScheduleStatus(String scheduleId, ScheduleStatus target) {
        Schedule schedule = findSchedule(scheduleId);
        ScheduleStatus current = schedule.getStatus() != null ? schedule.getStatus() : ScheduleStatus.Draft;

        // Idempotent: asking for the status the schedule already has succeeds without changes.
        if (current == target) {
            return new ApiResponse<>(schedule, "Schedule is already " + target, true);
        }
        boolean allowed = switch (current) {
            case Draft -> target == ScheduleStatus.Published || target == ScheduleStatus.Archived;
            case Published -> target == ScheduleStatus.Draft || target == ScheduleStatus.Archived;
            case Archived -> false;
        };
        if (!allowed) {
            throw new ConflictException("Cannot move a schedule from " + current + " to " + target);
        }

        schedule.setStatus(target);
        Schedule saved = scheduleRepository.save(schedule);
        recordChange(saved, "STATUS_CHANGED", null, null, null, current + " -> " + target);
        String message = "Schedule is now " + target;
        if (target == ScheduleStatus.Published) {
            long critical = conflictRepository.findByScheduleId(scheduleId).stream()
                    .filter(c -> c.getSeverity() == ScheduleConflictSeverity.critical && !c.isResolved())
                    .count();
            if (critical > 0) {
                message += " (" + critical + " critical conflict(s) still open)";
            }
        }
        logger.info("Schedule {} moved from {} to {}", scheduleId, current, target);
        return new ApiResponse<>(saved, message, true);
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<SwapRequest>> getSwapRequests(SwapRequestStatus status) {
        List<SwapRequest> requests = status == null
                ? swapRequestRepository.findAllByOrderByCreatedAtDesc()
                : swapRequestRepository.findByStatusOrderByCreatedAtDesc(status);
        return new ApiResponse<>(requests, "Swap requests fetched successfully", true);
    }

    /** Dry-run of a manual create/edit: returns the conflicts the change would cause, saves nothing. */
    @Transactional(readOnly = true)
    public ApiResponse<List<ScheduleConflict>> previewShift(ShiftRequest request, String shiftId) {
        if (request == null) {
            throw new IllegalArgumentException("Invalid shift data");
        }
        Schedule schedule = resolveSchedule(request);
        assertWithinSchedule(schedule, request.getDate());

        List<Shift> others = schedule.getShifts().stream()
                .filter(sh -> shiftId == null || !shiftId.equals(sh.getId()))
                .collect(Collectors.toList());

        Shift candidate = new Shift();
        candidate.setSchedule(schedule);
        applyRequest(candidate, request);

        Set<String> nurseIds = new HashSet<>();
        others.forEach(sh -> nurseIds.addAll(sh.getAssignedNurses()));
        nurseIds.addAll(candidate.getAssignedNurses());
        List<ScheduleConflict> conflicts = scheduleValidator.validateCandidate(
                schedule.getId(), candidate, shiftId, others, loadNurses(nurseIds));

        boolean slotTaken = others.stream().anyMatch(sh -> sh.getDate().equals(request.getDate())
                && sh.getType() == request.getType()
                && Objects.equals(sh.getDepartment(), request.getDepartment()));
        if (slotTaken) {
            ScheduleConflict clash = new ScheduleConflict();
            clash.setScheduleId(schedule.getId());
            clash.setShiftId(shiftId);
            clash.setDate(request.getDate());
            clash.setType(ScheduleConflictType.overlap);
            clash.setSeverity(ScheduleConflictSeverity.critical);
            clash.setMessage("A " + request.getType() + " shift for " + request.getDepartment()
                    + " already exists on " + request.getDate());
            clash.setSuggestions(new ArrayList<>(List.of("Edit the existing shift instead")));
            conflicts.add(clash);
        }
        return new ApiResponse<>(conflicts, conflicts.isEmpty() ? "No conflicts" : conflicts.size() + " conflict(s)", true);
    }

    /**
     * Generates a schedule. With an Idempotency-Key, a retry of the same request returns the schedule created by the
     * first attempt instead of failing with "already exists"; reusing a key for a different request is rejected.
     */
    @Transactional
    public ApiResponse<ScheduleGenerationResult> generateSchedule(ScheduleGenerationParams params, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        String requestHash = null;
        if (key != null) {
            if (key.length() > 128) {
                throw new IllegalArgumentException("Idempotency-Key must be at most 128 characters");
            }
            requestHash = hashOf(params);
            Optional<IdempotencyKey> stored = idempotencyKeyRepository.findByScopeAndKeyValue(IDEMPOTENCY_SCOPE_GENERATE, key);
            if (stored.isPresent()) {
                IdempotencyKey record = stored.get();
                if (record.getExpiresAt() != null && record.getExpiresAt().isBefore(LocalDateTime.now())) {
                    idempotencyKeyRepository.delete(record);
                    idempotencyKeyRepository.flush();
                } else {
                    if (!Objects.equals(record.getRequestHash(), requestHash)) {
                        throw new ConflictException("This Idempotency-Key was already used with a different request");
                    }
                    return replayGeneration(record);
                }
            }
        }

        ApiResponse<ScheduleGenerationResult> response = createSchedule(params);

        if (key != null) {
            // Stored in the same transaction as the schedule, so the key exists if and only if the schedule does.
            IdempotencyKey record = new IdempotencyKey();
            record.setScope(IDEMPOTENCY_SCOPE_GENERATE);
            record.setKeyValue(key);
            record.setRequestHash(requestHash);
            record.setResourceId(response.getData().getSchedule().getId());
            record.setResponseStatus(200);
            record.setExpiresAt(LocalDateTime.now().plusHours(IDEMPOTENCY_TTL_HOURS));
            idempotencyKeyRepository.save(record);
        }
        return response;
    }

    private ApiResponse<ScheduleGenerationResult> replayGeneration(IdempotencyKey record) {
        Schedule schedule = scheduleRepository.findById(record.getResourceId())
                .orElseThrow(() -> new ConflictException("The schedule created by this request no longer exists"));
        schedule.getShifts().size();
        List<ScheduleConflict> conflicts = conflictRepository.findByScheduleId(schedule.getId());
        List<WorkloadData> stats = workloadDataRepository.findByMonthAndYear(schedule.getMonth(), schedule.getYear());
        return new ApiResponse<>(new ScheduleGenerationResult(schedule, conflicts, stats, null),
                "Schedule generated successfully (replayed)", true);
    }

    private String hashOf(Object payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not fingerprint the request", e);
        }
    }

    private ApiResponse<ScheduleGenerationResult> createSchedule(ScheduleGenerationParams params) {
        if (params == null || params.getMonth() < 1 || params.getMonth() > 12 || params.getYear() < 2000) {
            logger.warn("Invalid schedule generation parameters: {}", params);
            throw new IllegalArgumentException("Invalid month or year");
        }

        if (scheduleRepository.existsByMonthAndYear(params.getMonth(), params.getYear())) {
            logger.warn("Schedule already exists for month {} and year {}", params.getMonth(), params.getYear());
            throw new ConflictException("Schedule already exists for month " + params.getMonth() + " and year " + params.getYear());
        }

        ScheduleGenerationParams.Constraints constraints = params.effectiveConstraints();
        if (constraints.getMaxNursesPerShift() < constraints.getMinNursesPerShift()) {
            throw new IllegalArgumentException("maxNursesPerShift must be greater than or equal to minNursesPerShift");
        }

        List<Nurse> nurses = nurseRepository.findAll();
        if (nurses.isEmpty()) {
            logger.error("No nurses available for scheduling");
            throw new IllegalArgumentException("No nurses available for scheduling");
        }

        // The last days of the previous month's schedule still count for rest, consecutive-day and weekly-hours rules.
        LocalDate firstOfMonth = LocalDate.of(params.getYear(), params.getMonth(), 1);
        ScheduleGenerator.Request generation = new ScheduleGenerator.Request();
        generation.setYear(params.getYear());
        generation.setMonth(params.getMonth());
        generation.setNurses(nurses);
        generation.setConstraints(constraints);
        generation.setSeed(params.getSeed());
        generation.setHistory(loadHistoryBefore(firstOfMonth));

        ScheduleGenerator.Result generated = scheduleGenerator.generate(generation);
        if (generated.getUnfilledPositions() > 0) {
            logger.warn("{} nurse position(s) could not be filled for {}/{}",
                    generated.getUnfilledPositions(), params.getMonth(), params.getYear());
        }

        Schedule schedule = new Schedule();
        schedule.setMonth(params.getMonth());
        schedule.setYear(params.getYear());
        schedule.setGeneratedAt(LocalDateTime.now());
        schedule.setStatus(ScheduleStatus.Draft);
        List<Shift> shifts = generated.getShifts();
        shifts.forEach(sh -> sh.setSchedule(schedule));
        schedule.setShifts(shifts);
        Schedule savedSchedule = scheduleRepository.save(schedule);
        updateWorkloadData(savedSchedule);
        List<ScheduleConflict> conflicts = detectConflicts(savedSchedule);
        List<WorkloadData> stats = workloadDataRepository.findByMonthAndYear(savedSchedule.getMonth(), savedSchedule.getYear());

        logger.info("Generated schedule ID: {} for {}/{}", savedSchedule.getId(), params.getMonth(), params.getYear());
        return new ApiResponse<>(new ScheduleGenerationResult(savedSchedule, conflicts, stats, generated.getSeed()),
                "Schedule generated successfully", true);
    }

    /** Shifts of the schedule that ends right before {@code firstDay}, limited to the last 14 days. */
    private List<Shift> loadHistoryBefore(LocalDate firstDay) {
        LocalDate previousMonth = firstDay.minusMonths(1);
        Optional<Schedule> previous = scheduleRepository.findByMonthAndYear(
                previousMonth.getMonthValue(), previousMonth.getYear());
        if (previous.isEmpty()) {
            return new ArrayList<>();
        }
        LocalDate from = firstDay.minusDays(14);
        return previous.get().getShifts().stream()
                .filter(sh -> sh.getDate() != null && !sh.getDate().isBefore(from) && sh.getDate().isBefore(firstDay))
                .collect(Collectors.toList());
    }

    private void updateWorkloadData(Schedule schedule) {
        Map<String, WorkloadData> computed = new LinkedHashMap<>();

        for (Shift shift : schedule.getShifts()) {
            boolean isWeekend = shift.getDate().getDayOfWeek().getValue() >= 6;
            boolean isNight = shift.getType() == ShiftType.Night;
            double hours = ShiftTimes.hours(shift);

            for (String nurseId : shift.getAssignedNurses()) {
                WorkloadData data = computed.computeIfAbsent(nurseId, id -> {
                    WorkloadData wd = new WorkloadData();
                    wd.setNurseId(id);
                    wd.setMonth(schedule.getMonth());
                    wd.setYear(schedule.getYear());
                    return wd;
                });

                data.setShiftsCount(data.getShiftsCount() + 1);
                data.setTotalHours(data.getTotalHours() + hours);
                if (isNight) {
                    data.setNightShifts(data.getNightShifts() + 1);
                }
                if (isWeekend) {
                    data.setWeekendShifts(data.getWeekendShifts() + 1);
                }
            }
        }

        // Upsert by (nurse, month, year): the table has a unique key on it, and re-running must never duplicate.
        Map<String, WorkloadData> existing = new HashMap<>();
        for (WorkloadData row : workloadDataRepository.findByMonthAndYear(schedule.getMonth(), schedule.getYear())) {
            existing.putIfAbsent(row.getNurseId(), row);
        }

        LocalDateTime now = LocalDateTime.now();
        List<WorkloadData> toSave = new ArrayList<>();
        for (WorkloadData fresh : computed.values()) {
            WorkloadData row = existing.remove(fresh.getNurseId());
            if (row == null) {
                row = fresh;
                row.setCreatedAt(now);
            } else {
                row.setShiftsCount(fresh.getShiftsCount());
                row.setTotalHours(fresh.getTotalHours());
                row.setNightShifts(fresh.getNightShifts());
                row.setWeekendShifts(fresh.getWeekendShifts());
            }
            row.setUpdatedAt(now);
            toSave.add(row);
        }

        // Nurses that no longer have any shift this month
        workloadDataRepository.deleteAll(existing.values());
        workloadDataRepository.saveAll(toSave);
        logger.debug("Updated workload data for {} nurses", toSave.size());
    }

    /** Conflicts are derived data: drop the previous set for the schedule and store the current one. */
    private List<ScheduleConflict> detectConflicts(Schedule schedule) {
        conflictRepository.deleteByScheduleId(schedule.getId());

        Set<String> nurseIds = new HashSet<>();
        schedule.getShifts().forEach(sh -> nurseIds.addAll(sh.getAssignedNurses()));
        List<ScheduleConflict> conflicts = scheduleValidator.validate(
                schedule.getId(), schedule.getShifts(), loadNurses(nurseIds));

        conflictRepository.saveAll(conflicts);
        logger.debug("Detected {} conflicts for schedule {}", conflicts.size(), schedule.getId());
        return conflicts;
    }

    private Map<String, Nurse> loadNurses(Collection<String> nurseIds) {
        if (nurseIds.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Nurse> byId = new HashMap<>();
        for (Nurse nurse : nurseRepository.findAllById(nurseIds)) {
            byId.put(nurse.getId(), nurse);
        }
        return byId;
    }

    private Schedule findSchedule(String scheduleId) {
        if (scheduleId == null || scheduleId.isBlank()) {
            throw new IllegalArgumentException("Invalid schedule ID");
        }
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found"));
    }

    private void assertEditable(Schedule schedule) {
        if (schedule != null && schedule.getStatus() == ScheduleStatus.Archived) {
            throw new ConflictException("This schedule is archived and cannot be modified");
        }
    }

    @Transactional
    public ApiResponse<Shift> createShift(ShiftRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Invalid shift data");
        }
        Schedule schedule = resolveSchedule(request);
        assertEditable(schedule);
        assertWithinSchedule(schedule, request.getDate());
        assertNursesExist(request.getAssignedNurses());
        assertSlotFree(schedule, request, null);

        Shift shift = new Shift();
        shift.setSchedule(schedule);
        applyRequest(shift, request);
        Shift savedShift = shiftRepository.save(shift);
        ensureInSchedule(schedule, savedShift);
        refreshDerivedData(schedule);
        auditIfPublished(schedule, "SHIFT_CREATED", savedShift.getId(), null, describe(savedShift));
        logger.info("Created shift ID: {}", savedShift.getId());
        return new ApiResponse<>(savedShift, "Shift created successfully", true);
    }

    @Transactional
    public ApiResponse<Shift> updateShift(String shiftId, ShiftRequest request) {
        if (shiftId == null || request == null) {
            logger.warn("Invalid shift ID or data: shiftId={}", shiftId);
            throw new IllegalArgumentException("Invalid shift ID or data");
        }
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> {
                    logger.error("Shift not found: {}", shiftId);
                    return new IllegalArgumentException("Shift not found");
                });
        if (request.getVersion() != null && !request.getVersion().equals(shift.getVersion())) {
            throw new ConflictException("Shift was modified by someone else. Reload it and try again.");
        }

        // Shifts created before schedule_id existed may still be unattached; attach them on first edit.
        Schedule schedule = shift.getSchedule() != null ? shift.getSchedule() : resolveSchedule(request);
        assertEditable(schedule);
        assertWithinSchedule(schedule, request.getDate());
        assertNursesExist(request.getAssignedNurses());
        assertSlotFree(schedule, request, shiftId);

        shift.setSchedule(schedule);
        applyRequest(shift, request);
        Shift savedShift = shiftRepository.save(shift);
        ensureInSchedule(schedule, savedShift);
        refreshDerivedData(schedule);
        auditIfPublished(schedule, "SHIFT_UPDATED", savedShift.getId(), null, describe(savedShift));
        logger.info("Updated shift ID: {}", savedShift.getId());
        return new ApiResponse<>(savedShift, "Shift updated successfully", true);
    }

    @Transactional
    public ApiResponse<Void> deleteShift(String shiftId) {
        if (shiftId == null) {
            logger.warn("Invalid shift ID: {}", shiftId);
            throw new IllegalArgumentException("Invalid shift ID");
        }
        // Idempotent: deleting a shift that is already gone is a success, so retries are safe.
        if (!shiftRepository.existsById(shiftId)) {
            logger.info("Shift {} already deleted", shiftId);
            return new ApiResponse<>(null, "Shift already deleted", true);
        }
        Shift existing = shiftRepository.findById(shiftId).orElse(null);
        Schedule schedule = existing != null ? existing.getSchedule() : null;
        assertEditable(schedule);
        conflictRepository.deleteByShiftId(shiftId);
        String deletedDescription = existing != null ? describe(existing) : null;
        if (schedule != null) {
            schedule.getShifts().removeIf(sh -> shiftId.equals(sh.getId()));
        }
        shiftRepository.deleteById(shiftId);
        if (schedule != null) {
            refreshDerivedData(schedule);
            auditIfPublished(schedule, "SHIFT_DELETED", shiftId, null, deletedDescription);
        }
        logger.info("Deleted shift ID: {}", shiftId);
        return new ApiResponse<>(null, "Shift deleted successfully", true);
    }

    /**
     * Reschedules a date range of a schedule. Past dates are never touched. By default this is a dry run that returns
     * the proposed changes; applying needs the {@code revision} and {@code seed} of that preview, so the schedule
     * cannot have changed in between and the result matches what the manager approved.
     */
    @Transactional
    public ApiResponse<RescheduleResult> reschedule(String scheduleId, RescheduleRequest request) {
        Schedule schedule = findSchedule(scheduleId);
        assertEditable(schedule);

        LocalDate monthStart = LocalDate.of(schedule.getYear(), schedule.getMonth(), 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        LocalDate from = request.getFromDate();
        if (from.isBefore(monthStart)) from = monthStart;
        if (from.isBefore(today())) from = today();           // the past is immutable
        LocalDate to = request.getToDate() != null ? request.getToDate() : monthEnd;
        if (to.isAfter(monthEnd)) to = monthEnd;
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("There is nothing to reschedule: the range is in the past or outside the schedule's month");
        }

        Set<String> excluded = request.getExcludeNurseIds() == null ? new HashSet<>() : request.getExcludeNurseIds();
        assertNursesExist(excluded);

        boolean dryRun = request.isDryRun();
        String revision = revisionOf(schedule);
        if (!dryRun) {
            if (request.getRevision() == null || request.getSeed() == null) {
                throw new IllegalArgumentException("revision and seed from the preview are required to apply a reschedule");
            }
            if (!revision.equals(request.getRevision())) {
                throw new ConflictException("The schedule changed after the preview was made. Preview the reschedule again.");
            }
        }

        List<Nurse> nurses = nurseRepository.findAll();
        if (nurses.isEmpty()) {
            throw new IllegalArgumentException("No nurses available for scheduling");
        }

        ReschedulePlanner.Input input = new ReschedulePlanner.Input();
        input.setScheduleId(schedule.getId());
        input.setYear(schedule.getYear());
        input.setMonth(schedule.getMonth());
        input.setNurses(nurses);
        input.setScheduleShifts(new ArrayList<>(schedule.getShifts()));
        input.setNeighbourShifts(loadNeighbourShifts(schedule, from.minusDays(14), to.plusDays(14)));
        input.setFrom(from);
        input.setTo(to);
        input.setMode(request.getMode() != null ? request.getMode() : RescheduleMode.FILL_GAPS);
        input.setExcludedNurseIds(excluded);
        input.setLockedShiftIds(request.getLockedShiftIds() == null ? new HashSet<>() : request.getLockedShiftIds());
        input.setConstraints(request.getConstraints() != null ? request.getConstraints()
                : new ScheduleGenerationParams.Constraints());
        input.setSeed(request.getSeed());

        ReschedulePlanner.Plan plan = reschedulePlanner.plan(input);
        RescheduleResult result = plan.getResult();
        result.setRevision(revision);

        if (dryRun) {
            return new ApiResponse<>(result, result.getChanges().isEmpty()
                    ? "Nothing needs to change" : result.getChanges().size() + " shift(s) would change", true);
        }

        Map<String, Shift> existing = new HashMap<>();
        schedule.getShifts().forEach(sh -> existing.put(sh.getId(), sh));
        for (RescheduleResult.ShiftChange change : result.getChanges()) {
            if (change.isNewShift()) {
                Shift template = plan.getNewShiftsByKey().get(ScheduleGenerator.slotKey(change.getDate(), change.getType()));
                Shift created = new Shift();
                created.setSchedule(schedule);
                created.setDate(template.getDate());
                created.setType(template.getType());
                created.setDepartment(template.getDepartment());
                created.setStartTime(template.getStartTime());
                created.setEndTime(template.getEndTime());
                created.setRequiredStaff(template.getRequiredStaff());
                created.setAssignedNurses(new LinkedHashSet<>(template.getAssignedNurses()));
                Shift saved = shiftRepository.save(created);
                schedule.getShifts().add(saved);
            } else {
                Shift shift = existing.get(change.getShiftId());
                shift.getAssignedNurses().removeAll(change.getRemoved());
                shift.getAssignedNurses().addAll(change.getAdded());
                shiftRepository.save(shift);
            }
        }

        refreshDerivedData(schedule);
        recordChange(schedule, "RESCHEDULE", null, null, request.getReason(),
                String.format("%s %s to %s: %d shift(s) changed, %d created, +%d/-%d nurse assignments, excluded=%s",
                        input.getMode(), from, to, result.getShiftsChanged(), result.getShiftsCreated(),
                        result.getNursesAdded(), result.getNursesRemoved(), excluded));

        result.setApplied(true);
        result.setRevision(null);   // the schedule has a new revision now; reload it
        result.setConflicts(conflictRepository.findByScheduleId(schedule.getId()));
        logger.info("Rescheduled schedule {} from {} to {}", scheduleId, from, to);
        return new ApiResponse<>(result, "Reschedule applied", true);
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<ScheduleChange>> getChanges(String scheduleId) {
        findSchedule(scheduleId);
        return new ApiResponse<>(scheduleChangeRepository.findByScheduleIdOrderByCreatedAtDesc(scheduleId),
                "Changes fetched successfully", true);
    }

    /** Re-checks the upcoming part of every open schedule a nurse works in, e.g. after their availability changed. */
    @Transactional
    public void revalidateSchedulesForNurse(String nurseId) {
        for (Schedule schedule : scheduleRepository.findOpenWithUpcomingShiftsOf(nurseId, today(), ScheduleStatus.Archived)) {
            detectConflicts(schedule);
        }
    }

    @Transactional(readOnly = true)
    public long countUpcomingShifts(String nurseId) {
        return shiftRepository.countUpcomingForNurse(nurseId, today());
    }

    /** Neighbouring months' shifts near the range: they constrain rest and consecutive-day rules at the edges. */
    private List<Shift> loadNeighbourShifts(Schedule schedule, LocalDate windowStart, LocalDate windowEnd) {
        LocalDate first = LocalDate.of(schedule.getYear(), schedule.getMonth(), 1);
        List<Shift> result = new ArrayList<>();
        for (LocalDate month : List.of(first.minusMonths(1), first.plusMonths(1))) {
            scheduleRepository.findByMonthAndYear(month.getMonthValue(), month.getYear()).ifPresent(neighbour -> {
                for (Shift sh : neighbour.getShifts()) {
                    if (sh.getDate() != null && !sh.getDate().isBefore(windowStart) && !sh.getDate().isAfter(windowEnd)) {
                        result.add(sh);
                    }
                }
            });
        }
        return result;
    }

    /** Changes whenever any shift of the schedule or its assignments change; used to detect a stale preview. */
    private String revisionOf(Schedule schedule) {
        List<String> parts = new ArrayList<>();
        for (Shift sh : schedule.getShifts()) {
            List<String> nurses = new ArrayList<>(sh.getAssignedNurses());
            Collections.sort(nurses);
            parts.add(sh.getId() + ":" + sh.getVersion() + ":" + String.join(",", nurses));
        }
        Collections.sort(parts);
        return hashOf(String.join(";", parts));
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return "system";
        }
        return authentication.getName();
    }

    private void recordChange(Schedule schedule, String action, String shiftId, String nurseId, String reason, String details) {
        ScheduleChange change = new ScheduleChange();
        change.setScheduleId(schedule.getId());
        change.setShiftId(shiftId);
        change.setNurseId(nurseId);
        change.setAction(action);
        change.setActor(currentActor());
        change.setReason(reason);
        change.setDetails(details);
        scheduleChangeRepository.save(change);
    }

    /** Edits to a published schedule are what people need an audit trail for; drafts are not logged. */
    private void auditIfPublished(Schedule schedule, String action, String shiftId, String nurseId, String details) {
        if (schedule != null && schedule.getStatus() == ScheduleStatus.Published) {
            recordChange(schedule, action, shiftId, nurseId, null, details);
        }
    }

    private String describe(Shift shift) {
        return shift.getDate() + " " + shift.getType() + " " + shift.getAssignedNurses();
    }

    /** Conflicts and workload are derived from the shifts, so they are refreshed whenever the shifts change. */
    private void refreshDerivedData(Schedule schedule) {
        updateWorkloadData(schedule);
        detectConflicts(schedule);
    }

    /** Schedule.shifts is the inverse side of the relationship; keep the in-memory collection in step with the shift. */
    private void ensureInSchedule(Schedule schedule, Shift shift) {
        boolean present = schedule.getShifts().stream().anyMatch(sh -> shift.getId().equals(sh.getId()));
        if (!present) {
            schedule.getShifts().add(shift);
        }
    }

    private Schedule resolveSchedule(ShiftRequest request) {
        if (request.getScheduleId() != null && !request.getScheduleId().isBlank()) {
            return scheduleRepository.findById(request.getScheduleId())
                    .orElseThrow(() -> new IllegalArgumentException("Schedule not found"));
        }
        int month = request.getDate().getMonthValue();
        int year = request.getDate().getYear();
        return scheduleRepository.findByMonthAndYear(month, year)
                .orElseThrow(() -> new IllegalArgumentException("No schedule exists for " + month + "/" + year));
    }

    private void assertWithinSchedule(Schedule schedule, LocalDate date) {
        if (date.getMonthValue() != schedule.getMonth() || date.getYear() != schedule.getYear()) {
            throw new IllegalArgumentException("Shift date " + date + " is outside schedule "
                    + schedule.getMonth() + "/" + schedule.getYear());
        }
    }

    private void assertNursesExist(Collection<String> nurseIds) {
        if (nurseIds == null || nurseIds.isEmpty()) {
            return;
        }
        Set<String> distinct = new HashSet<>(nurseIds);
        if (nurseRepository.findAllById(distinct).size() != distinct.size()) {
            throw new IllegalArgumentException("One or more assigned nurses do not exist");
        }
    }

    /** At most one shift per (schedule, date, type, department). The database enforces it too; this gives a clean 409. */
    private void assertSlotFree(Schedule schedule, ShiftRequest request, String excludingShiftId) {
        Optional<Shift> clash = shiftRepository.findBySchedule_IdAndDateAndTypeAndDepartment(
                schedule.getId(), request.getDate(), request.getType(), request.getDepartment());
        if (clash.isPresent() && !clash.get().getId().equals(excludingShiftId)) {
            throw new ConflictException("A " + request.getType() + " shift for " + request.getDepartment()
                    + " already exists on " + request.getDate());
        }
    }

    private void applyRequest(Shift shift, ShiftRequest request) {
        shift.setDate(request.getDate());
        shift.setStartTime(request.getStartTime());
        shift.setEndTime(request.getEndTime());
        shift.setType(request.getType());
        shift.setDepartment(request.getDepartment());
        shift.setRequiredStaff(request.getRequiredStaff());

        shift.getAssignedNurses().clear();
        if (request.getAssignedNurses() != null) {
            shift.getAssignedNurses().addAll(request.getAssignedNurses());
        }
        shift.getRequirements().clear();
        if (request.getRequirements() != null) {
            shift.getRequirements().addAll(request.getRequirements());
        }
    }

    @Transactional
    public ApiResponse<SwapRequest> createSwapRequest(SwapRequestCreate dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Invalid swap request data");
        }
        String targetShiftId = dto.getTargetShiftId() == null || dto.getTargetShiftId().isBlank()
                ? null : dto.getTargetShiftId();

        if (dto.getRequesterId().equals(dto.getTargetId())) {
            throw new IllegalArgumentException("A nurse cannot swap a shift with themselves");
        }
        assertNursesExist(List.of(dto.getRequesterId(), dto.getTargetId()));

        Shift a = shiftRepository.findById(dto.getShiftId())
                .orElseThrow(() -> new IllegalArgumentException("Shift not found"));
        Shift b = targetShiftId == null ? null : shiftRepository.findById(targetShiftId)
                .orElseThrow(() -> new IllegalArgumentException("The offered shift was not found"));
        assertSwappable(a);
        if (b != null) {
            assertSwappable(b);
        }

        if (swapRequestRepository.existsByShiftIdAndRequesterIdAndStatus(
                dto.getShiftId(), dto.getRequesterId(), SwapRequestStatus.Pending)) {
            throw new ConflictException("A swap request for this shift is already pending");
        }

        Map<String, Set<String>> plan = swapPlanner.plan(a, b, dto.getRequesterId(), dto.getTargetId());
        List<String> problems = introducedConflicts(b == null ? List.of(a) : List.of(a, b), plan);
        if (!problems.isEmpty()) {
            throw new ConflictException("This swap would cause conflicts: " + String.join("; ", problems));
        }

        SwapRequest request = new SwapRequest();
        request.setRequesterId(dto.getRequesterId());
        request.setTargetId(dto.getTargetId());
        request.setShiftId(dto.getShiftId());
        request.setTargetShiftId(targetShiftId);
        request.setReason(dto.getReason());
        request.setAutoMatched(dto.isAutoMatched());
        request.setStatus(SwapRequestStatus.Pending);
        request.setCreatedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());
        SwapRequest savedRequest = swapRequestRepository.save(request);
        logger.info("Created swap request ID: {}", savedRequest.getId());
        return new ApiResponse<>(savedRequest, "Swap request created successfully", true);
    }

    /**
     * Approves a pending swap and moves the nurses in the same transaction. Approving an already approved request is a
     * no-op that returns the same result; approving a rejected or cancelled one is a 409.
     */
    @Transactional
    public ApiResponse<SwapRequest> approveSwapRequest(String requestId, String reviewer) {
        SwapRequest request = lockSwapRequest(requestId);

        if (request.getStatus() == SwapRequestStatus.Approved) {
            return new ApiResponse<>(request, "Swap request already approved", true);
        }
        if (request.getStatus() != SwapRequestStatus.Pending) {
            throw new ConflictException("Cannot approve a swap request that is " + request.getStatus());
        }

        Shift a = shiftRepository.findById(request.getShiftId())
                .orElseThrow(() -> new ConflictException("The shift no longer exists"));
        Shift b = request.getTargetShiftId() == null ? null : shiftRepository.findById(request.getTargetShiftId())
                .orElseThrow(() -> new ConflictException("The offered shift no longer exists"));

        Map<String, Set<String>> plan;
        try {
            assertSwappable(a);
            if (b != null) {
                assertSwappable(b);
            }
            // The roster may have changed since the request was made, so everything is checked again.
            plan = swapPlanner.plan(a, b, request.getRequesterId(), request.getTargetId());
        } catch (IllegalArgumentException e) {
            throw new ConflictException(e.getMessage());
        }
        List<Shift> affected = b == null ? List.of(a) : List.of(a, b);
        List<String> problems = introducedConflicts(affected, plan);
        if (!problems.isEmpty()) {
            throw new ConflictException("This swap would cause conflicts: " + String.join("; ", problems));
        }

        for (Shift shift : affected) {
            shift.getAssignedNurses().clear();
            shift.getAssignedNurses().addAll(plan.get(shift.getId()));
        }
        shiftRepository.saveAll(affected);

        request.setStatus(SwapRequestStatus.Approved);
        request.setUpdatedAt(LocalDateTime.now());
        request.setReviewedBy(reviewer);
        SwapRequest savedRequest = swapRequestRepository.save(request);

        // Other open requests that involve these shifts are now based on stale assignments.
        Set<String> shiftIds = new HashSet<>();
        affected.forEach(sh -> shiftIds.add(sh.getId()));
        for (SwapRequest other : swapRequestRepository.findOpenForShifts(SwapRequestStatus.Pending, shiftIds)) {
            if (!other.getId().equals(savedRequest.getId())) {
                other.setStatus(SwapRequestStatus.Cancelled);
                other.setUpdatedAt(LocalDateTime.now());
                other.setReviewNotes("Superseded: the shift assignments changed (swap request " + savedRequest.getId() + " was approved)");
                swapRequestRepository.save(other);
            }
        }

        refreshDerivedDataFor(affected);
        for (Shift shift : affected) {
            auditIfPublished(shift.getSchedule(), "SWAP_APPROVED", shift.getId(), request.getRequesterId(),
                    "swap " + savedRequest.getId() + ": " + describe(shift));
        }
        logger.info("Approved swap request ID: {}", savedRequest.getId());
        return new ApiResponse<>(savedRequest, "Swap request approved", true);
    }

    @Transactional
    public ApiResponse<SwapRequest> rejectSwapRequest(String requestId, String reviewer) {
        SwapRequest request = lockSwapRequest(requestId);
        if (request.getStatus() == SwapRequestStatus.Rejected) {
            return new ApiResponse<>(request, "Swap request already rejected", true);
        }
        if (request.getStatus() != SwapRequestStatus.Pending) {
            throw new ConflictException("Cannot reject a swap request that is " + request.getStatus());
        }
        request.setStatus(SwapRequestStatus.Rejected);
        request.setUpdatedAt(LocalDateTime.now());
        request.setReviewedBy(reviewer);
        SwapRequest savedRequest = swapRequestRepository.save(request);
        logger.info("Rejected swap request ID: {}", savedRequest.getId());
        return new ApiResponse<>(savedRequest, "Swap request rejected", true);
    }

    @Transactional
    public ApiResponse<SwapRequest> cancelSwapRequest(String requestId) {
        SwapRequest request = lockSwapRequest(requestId);
        if (request.getStatus() == SwapRequestStatus.Cancelled) {
            return new ApiResponse<>(request, "Swap request already cancelled", true);
        }
        if (request.getStatus() != SwapRequestStatus.Pending) {
            throw new ConflictException("Cannot cancel a swap request that is " + request.getStatus());
        }
        request.setStatus(SwapRequestStatus.Cancelled);
        request.setUpdatedAt(LocalDateTime.now());
        SwapRequest savedRequest = swapRequestRepository.save(request);
        logger.info("Cancelled swap request ID: {}", savedRequest.getId());
        return new ApiResponse<>(savedRequest, "Swap request cancelled", true);
    }

    /**
     * Moves a nurse from one shift to another (or adds/removes them) in a single transaction, so a failure can never
     * leave the nurse removed from the source but not added to the target. Repeating a completed move is a no-op.
     * Conflicts the move causes are reported through the refreshed conflict list rather than blocking the manager.
     */
    @Transactional
    public ApiResponse<List<Shift>> moveNurse(MoveNurseRequest request) {
        String nurseId = request.getNurseId();
        assertNursesExist(List.of(nurseId));

        Shift from = blankToNull(request.getFromShiftId()) == null ? null
                : shiftRepository.findById(request.getFromShiftId())
                        .orElseThrow(() -> new IllegalArgumentException("Source shift not found"));
        Shift to = blankToNull(request.getToShiftId()) == null ? null
                : shiftRepository.findById(request.getToShiftId())
                        .orElseThrow(() -> new IllegalArgumentException("Destination shift not found"));

        boolean removedAlready = from == null || !from.getAssignedNurses().contains(nurseId);
        boolean addedAlready = to == null || to.getAssignedNurses().contains(nurseId);
        if (removedAlready && addedAlready) {
            List<Shift> unchanged = new ArrayList<>();
            if (from != null) unchanged.add(from);
            if (to != null) unchanged.add(to);
            return new ApiResponse<>(unchanged, "Assignment already up to date", true);
        }
        if (from != null && removedAlready) {
            throw new ConflictException("The nurse is not assigned to the source shift");
        }

        List<Shift> changed = new ArrayList<>();
        if (from != null) {
            assertEditable(from.getSchedule());
            from.getAssignedNurses().remove(nurseId);
            changed.add(from);
        }
        if (to != null && !addedAlready) {
            assertEditable(to.getSchedule());
            to.getAssignedNurses().add(nurseId);
            changed.add(to);
        }
        shiftRepository.saveAll(changed);
        refreshDerivedDataFor(changed);
        for (Shift shift : changed) {
            boolean removedFrom = shift == from;
            auditIfPublished(shift.getSchedule(), "NURSE_MOVED", shift.getId(), nurseId,
                    (removedFrom ? "removed from " : "added to ") + describe(shift));
        }
        return new ApiResponse<>(changed, "Nurse moved successfully", true);
    }

    private SwapRequest lockSwapRequest(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Invalid swap request ID");
        }
        return swapRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> {
                    logger.error("Swap request not found: {}", requestId);
                    return new IllegalArgumentException("Swap request not found");
                });
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(appTimezone));
    }

    private void assertSwappable(Shift shift) {
        if (shift.getSchedule() == null) {
            throw new IllegalArgumentException("The shift is not part of a schedule");
        }
        if (shift.getSchedule().getStatus() == ScheduleStatus.Archived) {
            throw new IllegalArgumentException("The schedule is archived");
        }
        if (shift.getDate().isBefore(today())) {
            throw new IllegalArgumentException("Past shifts cannot be swapped");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void refreshDerivedDataFor(Collection<Shift> shifts) {
        Map<String, Schedule> schedules = new LinkedHashMap<>();
        for (Shift shift : shifts) {
            if (shift.getSchedule() != null) {
                schedules.putIfAbsent(shift.getSchedule().getId(), shift.getSchedule());
            }
        }
        schedules.values().forEach(this::refreshDerivedData);
    }

    /**
     * Conflicts that would exist after applying {@code newAssignments} but do not exist now, as readable messages.
     * Works on transient copies of the affected shifts so nothing managed is modified (and nothing is flushed).
     */
    private List<String> introducedConflicts(List<Shift> affected, Map<String, Set<String>> newAssignments) {
        Map<String, Schedule> schedules = new LinkedHashMap<>();
        for (Shift shift : affected) {
            if (shift.getSchedule() == null) {
                throw new IllegalArgumentException("The shift is not part of a schedule");
            }
            schedules.putIfAbsent(shift.getSchedule().getId(), shift.getSchedule());
        }

        List<String> messages = new ArrayList<>();
        for (Schedule schedule : schedules.values()) {
            List<Shift> current = new ArrayList<>(schedule.getShifts());
            List<Shift> proposed = new ArrayList<>();
            for (Shift shift : current) {
                Set<String> replacement = newAssignments.get(shift.getId());
                proposed.add(replacement == null ? shift : copyWithAssignments(shift, replacement));
            }

            Set<String> nurseIds = new HashSet<>();
            current.forEach(sh -> nurseIds.addAll(sh.getAssignedNurses()));
            proposed.forEach(sh -> nurseIds.addAll(sh.getAssignedNurses()));
            Map<String, Nurse> nurses = loadNurses(nurseIds);

            Set<String> before = new HashSet<>();
            for (ScheduleConflict c : scheduleValidator.validate(schedule.getId(), current, nurses)) {
                before.add(conflictKey(c));
            }
            for (ScheduleConflict c : scheduleValidator.validate(schedule.getId(), proposed, nurses)) {
                if (!before.contains(conflictKey(c))) {
                    messages.add(c.getMessage());
                }
            }
        }
        return messages;
    }

    private String conflictKey(ScheduleConflict c) {
        return c.getType() + "|" + c.getShiftId() + "|" + c.getNurseId();
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
}