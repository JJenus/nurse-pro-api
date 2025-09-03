package com.surf.nursepro.nurse_pro_api.entity;

import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Entity
@Table(name = "shifts")
public class Shift {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private LocalDate date;
    private String startTime;
    private String endTime;

    @Enumerated(EnumType.STRING)
    private ShiftType type;

    private String department;
    private int requiredStaff;

    @ElementCollection
    private List<String> assignedNurses;

    @ElementCollection
    private List<String> requirements;
}