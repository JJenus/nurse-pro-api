package com.surf.nursepro.nurse_pro_api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Request body of POST /schedules/generate. Field names match the frontend's ScheduleGenerationParams. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScheduleGenerationParams {
    @Min(value = 1, message = "month must be between 1 and 12")
    @Max(value = 12, message = "month must be between 1 and 12")
    private int month;

    @Min(value = 2000, message = "year must be 2000 or later")
    @Max(value = 2200, message = "year is out of range")
    private int year;

    @Valid
    private Constraints constraints = new Constraints();

    /** Accepted for forward compatibility; rules are applied by the new generator (Phase 2). */
    private List<Map<String, Object>> rules = new ArrayList<>();

    /** Optional seed so a generation can be reproduced (used by the Phase 2 generator). */
    private Long seed;

    /** When a Draft schedule already exists for the month, replace it instead of failing with 409. */
    private boolean replaceDraft;

    /** Generate and return the schedule without saving anything. Send the same params with this off (and the returned seed) to save it. */
    private boolean preview;

    @JsonIgnore
    public Constraints effectiveConstraints() {
        return constraints != null ? constraints : new Constraints();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Constraints {
        @Min(value = 1, message = "minNursesPerShift must be at least 1")
        @Max(value = 50, message = "minNursesPerShift cannot exceed 50")
        private int minNursesPerShift = 2;

        @Min(value = 1, message = "maxNursesPerShift must be at least 1")
        @Max(value = 50, message = "maxNursesPerShift cannot exceed 50")
        private int maxNursesPerShift = 3;

        @Min(value = 1, message = "maxConsecutiveShifts must be at least 1")
        @Max(value = 31, message = "maxConsecutiveShifts cannot exceed 31")
        private int maxConsecutiveShifts = 5;

        @Min(value = 1, message = "maxConsecutiveDay must be at least 1")
        @Max(value = 31, message = "maxConsecutiveDay cannot exceed 31")
        private int maxConsecutiveDay = 3;

        @Min(value = 1, message = "maxConsecutiveNight must be at least 1")
        @Max(value = 31, message = "maxConsecutiveNight cannot exceed 31")
        private int maxConsecutiveNight = 3;

        /** Minimum rest between two shifts; enforced by the Phase 2 generator and validator. */
        @Min(value = 0, message = "minRestHours cannot be negative")
        @Max(value = 48, message = "minRestHours cannot exceed 48")
        private int minRestHours = 11;

        @Min(value = 0, message = "maxOvertimeHours cannot be negative")
        private int maxOvertimeHours = 0;

        @JsonIgnore
        @AssertTrue(message = "maxNursesPerShift must be greater than or equal to minNursesPerShift")
        public boolean isNurseRangeValid() {
            return maxNursesPerShift >= minNursesPerShift;
        }
    }
}
