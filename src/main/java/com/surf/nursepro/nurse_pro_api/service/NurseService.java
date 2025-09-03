package com.surf.nursepro.nurse_pro_api.service;

import com.surf.nursepro.nurse_pro_api.dto.ApiResponse;
import com.surf.nursepro.nurse_pro_api.dto.NurseStatus;
import com.surf.nursepro.nurse_pro_api.entity.Nurse;
import com.surf.nursepro.nurse_pro_api.enums.ExperienceLevel;
import com.surf.nursepro.nurse_pro_api.repository.NurseRepository;
import com.surf.nursepro.nurse_pro_api.repository.ShiftRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NurseService {
    private final NurseRepository nurseRepository;
    private final ShiftRepository shiftRepository;

    @Transactional
    public ApiResponse<Nurse> createNurse(Nurse nurse) {
        Nurse savedNurse = nurseRepository.save(nurse);
        return new ApiResponse<>(savedNurse, "Nurse created successfully", true);
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<Nurse>> getAllNurses(String department, String experienceLevel, String specialization) {
        // Build specification dynamically based on provided filters
        Specification<Nurse> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Filter by department if provided
            if (department != null && !department.trim().isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("department"), department.trim()));
            }

            // Filter by experienceLevel if provided
            if (experienceLevel != null && !experienceLevel.trim().isEmpty()) {
                try {
                    ExperienceLevel level = ExperienceLevel.valueOf(experienceLevel.trim());
                    predicates.add(criteriaBuilder.equal(root.get("experienceLevel"), level));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid experience level: " + experienceLevel);
                }
            }

            // Filter by specialization if provided
            if (specialization != null && !specialization.trim().isEmpty()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("specializations")),
                        "%" + specialization.trim().toLowerCase() + "%"
                ));
            }

            // Combine predicates with AND
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        // Execute query with the specification
        List<Nurse> nurses = nurseRepository.findAll(spec);

        // Return response
        return new ApiResponse<>(nurses, "Nurses retrieved successfully", true);
    }

    @Transactional(readOnly = true)
    public ApiResponse<Nurse> getNurseById(String id) {
        Nurse nurse = nurseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Nurse not found"));
        return new ApiResponse<>(nurse, "Nurse retrieved successfully", true);
    }

    @Transactional
    public ApiResponse<Nurse> updateNurse(String id, Nurse updatedNurse) {
        Nurse nurse = nurseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Nurse not found"));
        nurse.setFirstName(updatedNurse.getFirstName());
        nurse.setLastName(updatedNurse.getLastName());
        nurse.setEmail(updatedNurse.getEmail());
        nurse.setPhone(updatedNurse.getPhone());
        nurse.setDepartment(updatedNurse.getDepartment());
        nurse.setSpecializations(updatedNurse.getSpecializations());
        nurse.setExperienceLevel(updatedNurse.getExperienceLevel());
        nurse.setMaxHoursPerWeek(updatedNurse.getMaxHoursPerWeek());
        nurse.setPreferredShifts(updatedNurse.getPreferredShifts());
        nurse.setUnavailableDates(updatedNurse.getUnavailableDates());
        nurse.setUpdatedAt(LocalDateTime.now());
        Nurse savedNurse = nurseRepository.save(nurse);
        return new ApiResponse<>(savedNurse, "Nurse updated successfully", true);
    }

    @Transactional
    public ApiResponse<Void> deleteNurse(String id) {
        nurseRepository.deleteById(id);
        return new ApiResponse<>(null, "Nurse deleted successfully", true);
    }

    @Transactional
    public ApiResponse<List<Nurse>> bulkUploadNurses(List<Nurse> nurses) {
        System.out.println("Got here");
        List<Nurse> savedNurses = nurseRepository.saveAll(nurses);
        return new ApiResponse<>(savedNurses, "Nurses uploaded successfully", true);
    }

    @Transactional(readOnly = true)
    public ApiResponse<NurseStatus> getNurseStatus(String nurseId) {
        LocalDateTime now = LocalDateTime.now();
        NurseStatus status = new NurseStatus();
        status.setNurseId(nurseId);

        shiftRepository.findAll().stream()
                .filter(shift -> shift.getAssignedNurses().contains(nurseId))
                .filter(shift -> {
                    LocalDateTime shiftStart = shift.getDate().atTime(
                            LocalTime.parse(shift.getStartTime()));
                    LocalDateTime shiftEnd = shift.getDate().atTime(
                            LocalTime.parse(shift.getEndTime()));
                    return now.isAfter(shiftStart) && now.isBefore(shiftEnd);
                })
                .findFirst()
                .ifPresentOrElse(
                        shift -> {
                            status.setOnDuty(true);
                            status.setCurrentShiftId(shift.getId());
                            status.setShiftStartTime(shift.getDate().atTime(LocalTime.parse(shift.getStartTime())));
                            status.setShiftEndTime(shift.getDate().atTime(LocalTime.parse(shift.getEndTime())));
                        },
                        () -> status.setOnDuty(false)
                );

        return new ApiResponse<>(status, "Nurse status retrieved successfully", true);
    }
}