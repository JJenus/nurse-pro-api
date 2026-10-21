package com.surf.nursepro.nurse_pro_api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Moves a nurse between shifts in one atomic step. Leave fromShiftId empty to just add, toShiftId empty to just remove. */
@Data
public class MoveNurseRequest {
    @NotBlank(message = "nurseId is required")
    private String nurseId;

    private String fromShiftId;

    private String toShiftId;

    @JsonIgnore
    @AssertTrue(message = "Provide fromShiftId, toShiftId or both, and they must differ")
    public boolean isValidMove() {
        boolean hasFrom = fromShiftId != null && !fromShiftId.isBlank();
        boolean hasTo = toShiftId != null && !toShiftId.isBlank();
        return (hasFrom || hasTo) && !(hasFrom && hasTo && fromShiftId.equals(toShiftId));
    }
}
