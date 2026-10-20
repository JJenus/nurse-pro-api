package com.surf.nursepro.nurse_pro_api.entity;

import com.surf.nursepro.nurse_pro_api.enums.ScheduleStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "schedules",
        uniqueConstraints = @UniqueConstraint(name = "uk_schedules_year_month", columnNames = {"year", "month"}))
public class Schedule {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private int month;

    private int year;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Shift> shifts = new ArrayList<>();

    private LocalDateTime generatedAt;

    @Enumerated(EnumType.STRING)
    private ScheduleStatus status;

    @Version
    private Long version;
}
