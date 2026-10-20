package com.surf.nursepro.nurse_pro_api.dto;

import com.surf.nursepro.nurse_pro_api.enums.ScheduleStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScheduleStatusRequest {
    @NotNull(message = "status is required")
    private ScheduleStatus status;
}
