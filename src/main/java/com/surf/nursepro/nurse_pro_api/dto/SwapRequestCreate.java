package com.surf.nursepro.nurse_pro_api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for creating a swap request. The requester gives up {@code shiftId} to {@code targetId};
 * when {@code targetShiftId} is set the target hands that shift back (an exchange), otherwise it is a hand-off.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SwapRequestCreate {
    @NotBlank(message = "requesterId is required")
    private String requesterId;

    @NotBlank(message = "targetId is required")
    private String targetId;

    @NotBlank(message = "shiftId is required")
    private String shiftId;

    private String targetShiftId;

    @Size(max = 1000, message = "reason must be at most 1000 characters")
    private String reason;

    private boolean autoMatched;
}
