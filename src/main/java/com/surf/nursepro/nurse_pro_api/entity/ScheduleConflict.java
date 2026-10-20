package com.surf.nursepro.nurse_pro_api.entity;

import com.surf.nursepro.nurse_pro_api.enums.ScheduleConflictSeverity;
import com.surf.nursepro.nurse_pro_api.enums.ScheduleConflictType;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
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

    /** The schedule this conflict belongs to; conflicts are recomputed (delete + insert) per schedule. */
    private String scheduleId;

    private String shiftId;

    private LocalDate date;

    private boolean resolved;
    private String nurseId;
    private String message;

    @ElementCollection
    private List<String> suggestions;
}