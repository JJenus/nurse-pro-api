package com.surf.nursepro.nurse_pro_api.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
@Entity
@Table(name = "shifts",
        uniqueConstraints = @UniqueConstraint(name = "uk_shift_slot",
                columnNames = {"schedule_id", "date", "type", "department"}))
public class Shift {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", foreignKey = @ForeignKey(name = "fk_shifts_schedule"))
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Schedule schedule;

    private LocalDate date;

    private String startTime;

    private String endTime;

    @Enumerated(EnumType.STRING)
    private ShiftType type;

    private String department;

    private int requiredStaff;

    /** Nurse ids. A Set so a nurse can never be assigned twice to the same shift. */
    @ElementCollection
    private Set<String> assignedNurses = new LinkedHashSet<>();

    @ElementCollection
    private List<String> requirements = new ArrayList<>();

    @Version
    private Long version;

    @JsonProperty("scheduleId")
    public String getScheduleId() {
        return schedule != null ? schedule.getId() : null;
    }
}
