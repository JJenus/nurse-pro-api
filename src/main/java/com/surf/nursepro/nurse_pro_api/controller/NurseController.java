package com.surf.nursepro.nurse_pro_api.controller;

import com.surf.nursepro.nurse_pro_api.dto.ApiResponse;
import com.surf.nursepro.nurse_pro_api.dto.NurseStatus;
import com.surf.nursepro.nurse_pro_api.entity.Nurse;
import com.surf.nursepro.nurse_pro_api.service.NurseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/nurses")
@RequiredArgsConstructor
@Tag(name = "Nurse Management", description = "APIs for managing nurses")
public class NurseController {
    private final NurseService nurseService;

    @PostMapping
    @Operation(summary = "Create a new nurse")
    public ResponseEntity<ApiResponse<Nurse>> createNurse(@Valid @RequestBody Nurse nurse) {
        return ResponseEntity.ok(nurseService.createNurse(nurse));
    }

    @GetMapping
    @Operation(summary = "Get all nurses with optional filters")
    public ResponseEntity<ApiResponse<List<Nurse>>> getAllNurses(
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String experienceLevel,
            @RequestParam(required = false) String specialization) {
        return ResponseEntity.ok(nurseService.getAllNurses(department, experienceLevel, specialization));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get nurse by ID")
    public ResponseEntity<ApiResponse<Nurse>> getNurseById(@PathVariable String id) {
        return ResponseEntity.ok(nurseService.getNurseById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update nurse details")
    public ResponseEntity<ApiResponse<Nurse>> updateNurse(@PathVariable String id, @Valid @RequestBody Nurse nurse) {
        return ResponseEntity.ok(nurseService.updateNurse(id, nurse));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete nurse")
    public ResponseEntity<ApiResponse<Void>> deleteNurse(@PathVariable String id) {
        return ResponseEntity.ok(nurseService.deleteNurse(id));
    }

    @PostMapping("/bulk")
    @Operation(summary = "Bulk upload nurses")
    public ResponseEntity<ApiResponse<List<Nurse>>> bulkUploadNurses(@Valid @RequestBody List<Nurse> nurses) {
        return ResponseEntity.ok(nurseService.bulkUploadNurses(nurses));
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Get nurse current status")
    public ResponseEntity<ApiResponse<NurseStatus>> getNurseStatus(@PathVariable String id) {
        return ResponseEntity.ok(nurseService.getNurseStatus(id));
    }
}