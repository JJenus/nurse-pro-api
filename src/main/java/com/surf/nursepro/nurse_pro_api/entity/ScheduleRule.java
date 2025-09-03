package com.surf.nursepro.nurse_pro_api.entity;

import com.surf.nursepro.nurse_pro_api.enums.ScheduleRuleType;
import jakarta.persistence.*;
import lombok.Data;
import java.util.Map;

@Data
@Entity
@Table(name = "schedule_rules")
public class ScheduleRule {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String name;

    @Enumerated(EnumType.STRING)
    private ScheduleRuleType type;

    private int priority;
    private boolean enabled;

    @ElementCollection
    private Map<String, String> parameters;
}