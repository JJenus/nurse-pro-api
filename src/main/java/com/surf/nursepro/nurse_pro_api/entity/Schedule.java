package com.surf.nursepro.nurse_pro_api.entity;

import com.surf.nursepro.nurse_pro_api.enums.ScheduleStatus;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "schedules")
public class Schedule {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private int month;
    private int year;

    @OneToMany(cascade = CascadeType.ALL)
    private List<Shift> shifts;

    private LocalDateTime generatedAt;

    @Enumerated(EnumType.STRING)
    private ScheduleStatus status;
}