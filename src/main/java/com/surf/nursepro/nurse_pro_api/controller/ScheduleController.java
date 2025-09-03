package com.surf.nursepro.nurse_pro_api.controller;

import com.surf.nursepro.nurse_pro_api.dto.ApiResponse;
import com.surf.nursepro.nurse_pro_api.dto.ScheduleGenerationParams;
import com.surf.nursepro.nurse_pro_api.entity.Schedule;
import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.entity.SwapRequest;
import com.surf.nursepro.nurse_pro_api.entity.WorkloadData;
import com.surf.nursepro.nurse_pro_api.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
@Tag(name = "Schedule Management", description = "APIs for managing schedules and shifts")
public class ScheduleController {
    private final ScheduleService scheduleService;

    @GetMapping
    @Operation(summary = "Get all schedules")
    public ResponseEntity<ApiResponse<List<Schedule>>> getSchedules() {
        return ResponseEntity.ok(scheduleService.fetchSchedules());
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate new schedule")
    public ResponseEntity<ApiResponse<Schedule>> generateSchedule(@Valid @RequestBody ScheduleGenerationParams params) {
        return ResponseEntity.ok(scheduleService.generateSchedule(params));
    }

    @PostMapping("/shifts")
    @Operation(summary = "Create new shift")
    public ResponseEntity<ApiResponse<Shift>> createShift(@Valid @RequestBody Shift shift) {
        return ResponseEntity.ok(scheduleService.createShift(shift));
    }

    @PutMapping("/shifts/{shiftId}")
    @Operation(summary = "Update shift")
    public ResponseEntity<ApiResponse<Shift>> updateShift(@PathVariable String shiftId, @Valid @RequestBody Shift shift) {
        return ResponseEntity.ok(scheduleService.updateShift(shiftId, shift));
    }

    @DeleteMapping("/shifts/{shiftId}")
    @Operation(summary = "Delete shift")
    public ResponseEntity<ApiResponse<Void>> deleteShift(@PathVariable String shiftId) {
        return ResponseEntity.ok(scheduleService.deleteShift(shiftId));
    }

    @PostMapping("/swap-requests")
    @Operation(summary = "Create swap request")
    public ResponseEntity<ApiResponse<SwapRequest>> createSwapRequest(@Valid @RequestBody SwapRequest request) {
        return ResponseEntity.ok(scheduleService.createSwapRequest(request));
    }

    @PutMapping("/swap-requests/{requestId}/approve")
    @Operation(summary = "Approve swap request")
    public ResponseEntity<ApiResponse<SwapRequest>> approveSwapRequest(@PathVariable String requestId) {
        return ResponseEntity.ok(scheduleService.approveSwapRequest(requestId));
    }

    @PutMapping("/swap-requests/{requestId}/reject")
    @Operation(summary = "Reject swap request")
    public ResponseEntity<ApiResponse<SwapRequest>> rejectSwapRequest(@PathVariable String requestId) {
        return ResponseEntity.ok(scheduleService.rejectSwapRequest(requestId));
    }

    @GetMapping("/workload")
    @Operation(summary = "Get workload data")
    public ResponseEntity<ApiResponse<List<WorkloadData>>> getWorkloadData(
            @RequestParam String nurseId,
            @RequestParam int month,
            @RequestParam int year) {
        return ResponseEntity.ok(scheduleService.getWorkloadData(nurseId, month, year));
    }
}