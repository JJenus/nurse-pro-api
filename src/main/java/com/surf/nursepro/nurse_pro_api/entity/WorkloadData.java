package com.surf.nursepro.nurse_pro_api.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "workload_data")
public class WorkloadData {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String nurseId;
    private double totalHours;
    private int shiftsCount;
    private double overtimeHours;
    private int month;
    private int year;
    private int nightShifts;
    private int weekendShifts;
    private int consecutiveDays;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}