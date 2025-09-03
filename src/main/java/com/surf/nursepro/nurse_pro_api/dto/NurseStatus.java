package com.surf.nursepro.nurse_pro_api.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class NurseStatus {
    private String nurseId;
    private boolean isOnDuty;
    private String currentShiftId;
    private LocalDateTime shiftStartTime;
    private LocalDateTime shiftEndTime;
}