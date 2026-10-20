package com.surf.nursepro.nurse_pro_api.dto;

import com.surf.nursepro.nurse_pro_api.entity.Schedule;
import com.surf.nursepro.nurse_pro_api.entity.ScheduleConflict;
import com.surf.nursepro.nurse_pro_api.entity.WorkloadData;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Everything the UI needs after generating a schedule, in one response. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleGenerationResult {
    private Schedule schedule;
    private List<ScheduleConflict> conflicts;
    private List<WorkloadData> stats;
}
