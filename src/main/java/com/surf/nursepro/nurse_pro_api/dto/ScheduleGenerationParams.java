package com.surf.nursepro.nurse_pro_api.dto;

import com.surf.nursepro.nurse_pro_api.entity.ScheduleRule;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ScheduleGenerationParams {
    private int month;
    private int year;
    private List<ScheduleRule> rules;
    private Constraints constraints;

    @Data
    public static class Constraints {
        private Map<String, Integer> minStaffPerShift;
        private int maxConsecutiveShifts;
        private int minRestHours;
        private int maxOvertimeHours;
    }
}