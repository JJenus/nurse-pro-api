package com.surf.nursepro.nurse_pro_api.dto;

import com.surf.nursepro.nurse_pro_api.entity.ScheduleConflict;
import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RescheduleResult {
    private boolean applied;
    /** Pass back as {@code revision} to apply this preview; changes whenever a shift of the schedule changes. */
    private String revision;
    /** Pass back as {@code seed} to apply exactly this preview. */
    private long seed;
    private LocalDate from;
    private LocalDate to;
    private int shiftsChanged;
    private int shiftsCreated;
    private int nursesAdded;
    private int nursesRemoved;
    /** Nurse positions still below the required staff after rescheduling. */
    private int unfilledPositions;
    private List<ShiftChange> changes = new ArrayList<>();
    /** Conflicts of the schedule as it would be (preview) or is (applied) after the change. */
    private List<ScheduleConflict> conflicts = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShiftChange {
        /** Null for a shift that does not exist yet. */
        private String shiftId;
        private LocalDate date;
        private ShiftType type;
        private boolean newShift;
        private List<String> added = new ArrayList<>();
        private List<String> removed = new ArrayList<>();
    }
}
