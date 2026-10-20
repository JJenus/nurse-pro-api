package com.surf.nursepro.nurse_pro_api.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Audit log of changes to schedules and shifts (who, when, what, why). Written from Phase 3 onwards. */
@Data
@Entity
@Table(name = "schedule_changes")
public class ScheduleChange {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String scheduleId;

    private String shiftId;

    private String nurseId;

    @Column(nullable = false, length = 64)
    private String action;

    private String actor;

    @Column(length = 1000)
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
