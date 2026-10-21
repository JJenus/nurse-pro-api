package com.surf.nursepro.nurse_pro_api.dto;

import com.surf.nursepro.nurse_pro_api.enums.RescheduleMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Body of POST /schedules/{id}/reschedule. By default it is a dry run that returns the proposed changes; to apply them
 * send the same request with {@code dryRun=false} plus the {@code revision} and {@code seed} the preview returned.
 */
@Data
public class RescheduleRequest {
    @NotNull(message = "fromDate is required")
    private LocalDate fromDate;

    /** Last date to reschedule (inclusive); defaults to the end of the schedule's month. */
    private LocalDate toDate;

    private RescheduleMode mode = RescheduleMode.FILL_GAPS;

    /** Nurses that must not work in the range (sick leave, resignation...). They are removed from the range. */
    private Set<String> excludeNurseIds = new HashSet<>();

    /** Shifts to leave untouched when rebuilding the range. */
    private Set<String> lockedShiftIds = new HashSet<>();

    /** Rules to respect. When omitted, the rules the schedule was generated with are used. */
    @Valid
    private ScheduleGenerationParams.Constraints constraints;

    @Size(max = 1000, message = "reason must be at most 1000 characters")
    private String reason;

    private boolean dryRun = true;

    /** Required to apply: the schedule revision the preview was made against. */
    private String revision;

    /** Optional on preview; required to apply, so the applied result matches the preview. */
    private Long seed;
}
