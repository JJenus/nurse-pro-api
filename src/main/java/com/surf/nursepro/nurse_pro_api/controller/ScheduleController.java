package com.surf.nursepro.nurse_pro_api.controller;

import com.surf.nursepro.nurse_pro_api.dto.ApiResponse;
import com.surf.nursepro.nurse_pro_api.dto.ScheduleGenerationParams;
import com.surf.nursepro.nurse_pro_api.entity.Schedule;
import com.surf.nursepro.nurse_pro_api.dto.ScheduleGenerationResult;
import com.surf.nursepro.nurse_pro_api.dto.ScheduleStatusRequest;
import com.surf.nursepro.nurse_pro_api.dto.MoveNurseRequest;
import com.surf.nursepro.nurse_pro_api.dto.RescheduleRequest;
import com.surf.nursepro.nurse_pro_api.dto.RescheduleResult;
import com.surf.nursepro.nurse_pro_api.entity.ScheduleChange;
import com.surf.nursepro.nurse_pro_api.dto.ShiftRequest;
import com.surf.nursepro.nurse_pro_api.dto.SwapRequestCreate;
import com.surf.nursepro.nurse_pro_api.entity.ScheduleConflict;
import com.surf.nursepro.nurse_pro_api.enums.ScheduleStatus;
import com.surf.nursepro.nurse_pro_api.enums.SwapRequestStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.entity.SwapRequest;
import com.surf.nursepro.nurse_pro_api.entity.WorkloadData;
import com.surf.nursepro.nurse_pro_api.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
@Tag(name = "Schedule Management", description = "APIs for managing schedules and shifts")
public class ScheduleController {
    private final ScheduleService scheduleService;

    @GetMapping
    @Operation(summary = "List schedules, optionally filtered by month, year and status")
    public ResponseEntity<ApiResponse<List<Schedule>>> getSchedules(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) ScheduleStatus status) {
        return ResponseEntity.ok(scheduleService.fetchSchedules(month, year, status));
    }

    @GetMapping("/{scheduleId}")
    @Operation(summary = "Get one schedule with its shifts")
    public ResponseEntity<ApiResponse<Schedule>> getSchedule(@PathVariable String scheduleId) {
        return ResponseEntity.ok(scheduleService.getSchedule(scheduleId));
    }

    @GetMapping("/{scheduleId}/conflicts")
    @Operation(summary = "Get the stored conflicts of a schedule")
    public ResponseEntity<ApiResponse<List<ScheduleConflict>>> getConflicts(@PathVariable String scheduleId) {
        return ResponseEntity.ok(scheduleService.getConflicts(scheduleId));
    }

    @PostMapping("/{scheduleId}/validate")
    @Operation(summary = "Recompute conflicts and workload for a schedule")
    public ResponseEntity<ApiResponse<List<ScheduleConflict>>> validateSchedule(@PathVariable String scheduleId) {
        return ResponseEntity.ok(scheduleService.validateSchedule(scheduleId));
    }

    @PostMapping("/{scheduleId}/reschedule")
    @Operation(summary = "Preview (default) or apply a reschedule of a date range")
    public ResponseEntity<ApiResponse<RescheduleResult>> reschedule(
            @PathVariable String scheduleId, @Valid @RequestBody RescheduleRequest request) {
        return ResponseEntity.ok(scheduleService.reschedule(scheduleId, request));
    }

    @GetMapping("/{scheduleId}/changes")
    @Operation(summary = "Audit log of changes made to a schedule")
    public ResponseEntity<ApiResponse<List<ScheduleChange>>> getChanges(@PathVariable String scheduleId) {
        return ResponseEntity.ok(scheduleService.getChanges(scheduleId));
    }

    @PutMapping("/{scheduleId}/status")
    @Operation(summary = "Publish, unpublish or archive a schedule")
    public ResponseEntity<ApiResponse<Schedule>> updateScheduleStatus(
            @PathVariable String scheduleId, @Valid @RequestBody ScheduleStatusRequest request) {
        return ResponseEntity.ok(scheduleService.updateScheduleStatus(scheduleId, request.getStatus()));
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate new schedule")
    public ResponseEntity<ApiResponse<ScheduleGenerationResult>> generateSchedule(
            @Valid @RequestBody ScheduleGenerationParams params,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(scheduleService.generateSchedule(params, idempotencyKey));
    }

    @PostMapping("/shifts/move")
    @Operation(summary = "Move a nurse between shifts atomically")
    public ResponseEntity<ApiResponse<List<Shift>>> moveNurse(@Valid @RequestBody MoveNurseRequest request) {
        return ResponseEntity.ok(scheduleService.moveNurse(request));
    }

    @PostMapping("/shifts/validate")
    @Operation(summary = "Check a proposed shift (or edit, with shiftId) for conflicts without saving it")
    public ResponseEntity<ApiResponse<List<ScheduleConflict>>> previewShift(
            @Valid @RequestBody ShiftRequest shift, @RequestParam(required = false) String shiftId) {
        return ResponseEntity.ok(scheduleService.previewShift(shift, shiftId));
    }

    @PostMapping("/shifts")
    @Operation(summary = "Create new shift")
    public ResponseEntity<ApiResponse<Shift>> createShift(@Valid @RequestBody ShiftRequest shift) {
        return ResponseEntity.ok(scheduleService.createShift(shift));
    }

    @PutMapping("/shifts/{shiftId}")
    @Operation(summary = "Update shift")
    public ResponseEntity<ApiResponse<Shift>> updateShift(@PathVariable String shiftId, @Valid @RequestBody ShiftRequest shift) {
        return ResponseEntity.ok(scheduleService.updateShift(shiftId, shift));
    }

    @DeleteMapping("/shifts/{shiftId}")
    @Operation(summary = "Delete shift")
    public ResponseEntity<ApiResponse<Void>> deleteShift(@PathVariable String shiftId) {
        return ResponseEntity.ok(scheduleService.deleteShift(shiftId));
    }

    @PostMapping("/swap-requests")
    @Operation(summary = "Create swap request")
    public ResponseEntity<ApiResponse<SwapRequest>> createSwapRequest(@Valid @RequestBody SwapRequestCreate request) {
        return ResponseEntity.ok(scheduleService.createSwapRequest(request));
    }

    @GetMapping("/swap-requests")
    @Operation(summary = "List swap requests, optionally by status")
    public ResponseEntity<ApiResponse<List<SwapRequest>>> getSwapRequests(
            @RequestParam(required = false) SwapRequestStatus status) {
        return ResponseEntity.ok(scheduleService.getSwapRequests(status));
    }

    @PutMapping("/swap-requests/{requestId}/approve")
    @Operation(summary = "Approve swap request")
    public ResponseEntity<ApiResponse<SwapRequest>> approveSwapRequest(@PathVariable String requestId, Authentication authentication) {
        return ResponseEntity.ok(scheduleService.approveSwapRequest(requestId, reviewer(authentication)));
    }

    @PutMapping("/swap-requests/{requestId}/cancel")
    @Operation(summary = "Cancel a pending swap request")
    public ResponseEntity<ApiResponse<SwapRequest>> cancelSwapRequest(@PathVariable String requestId) {
        return ResponseEntity.ok(scheduleService.cancelSwapRequest(requestId));
    }

    @PutMapping("/swap-requests/{requestId}/reject")
    @Operation(summary = "Reject swap request")
    public ResponseEntity<ApiResponse<SwapRequest>> rejectSwapRequest(@PathVariable String requestId, Authentication authentication) {
        return ResponseEntity.ok(scheduleService.rejectSwapRequest(requestId, reviewer(authentication)));
    }

    @GetMapping("/workload")
    @Operation(summary = "Get workload data")
    public ResponseEntity<ApiResponse<List<WorkloadData>>> getWorkloadData(
            @RequestParam String nurseId,
            @RequestParam int month,
            @RequestParam int year) {
        return ResponseEntity.ok(scheduleService.getWorkloadData(nurseId, month, year));
    }

    @GetMapping("/export")
    @Operation(summary = "Export schedules for specified months and year")
    public ResponseEntity<byte[]> exportSchedules(
            @RequestParam List<Integer> months,
            @RequestParam int year,
            @RequestParam String format) {
        ApiResponse<byte[]> response = scheduleService.exportSchedules(months, year, format);
        String fileName = format.equalsIgnoreCase("pdf") ?
                "schedules-" + year + "-" + months.stream().map(String::valueOf).collect(Collectors.joining("_")) + ".pdf" :
                "schedules-" + year + "-" + months.stream().map(String::valueOf).collect(Collectors.joining("_")) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(format.equalsIgnoreCase("pdf") ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_OCTET_STREAM)
                .body(response.getData());
    }

    private String reviewer(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return "system";
        }
        return authentication.getName();
    }
}
