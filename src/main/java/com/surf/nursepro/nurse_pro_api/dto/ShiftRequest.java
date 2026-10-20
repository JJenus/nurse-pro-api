package com.surf.nursepro.nurse_pro_api.dto;

import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Request body for creating or updating a shift. scheduleId is optional: it defaults to the schedule of the date's month. */
@Data
public class ShiftRequest {
    private String scheduleId;

    @NotNull(message = "date is required")
    private LocalDate date;

    @NotBlank(message = "startTime is required")
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "startTime must be HH:mm")
    private String startTime;

    @NotBlank(message = "endTime is required")
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "endTime must be HH:mm")
    private String endTime;

    @NotNull(message = "type is required")
    private ShiftType type;

    @NotBlank(message = "department is required")
    @Size(max = 100, message = "department must be at most 100 characters")
    private String department;

    @Min(value = 0, message = "requiredStaff cannot be negative")
    @Max(value = 50, message = "requiredStaff cannot exceed 50")
    private int requiredStaff;

    private Set<String> assignedNurses = new LinkedHashSet<>();

    private List<String> requirements = new ArrayList<>();

    /** Optional: when sent on update, the update is rejected with 409 if the shift changed since this version. */
    private Long version;
}
