package com.surf.nursepro.nurse_pro_api.entity;

import com.surf.nursepro.nurse_pro_api.enums.ScheduleConflictSeverity;
import com.surf.nursepro.nurse_pro_api.enums.ScheduleConflictType;
import jakarta.persistence.*;
import lombok.Data;
import java.util.List;

@Data
@Entity
@Table(name = "schedule_conflicts")
public class ScheduleConflict {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Enumerated(EnumType.STRING)
    private ScheduleConflictType type;

    @Enumerated(EnumType.STRING)
    private ScheduleConflictSeverity severity;

    private String shiftId;
    private String nurseId;
    private String message;

    @ElementCollection
    private List<String> suggestions;
}