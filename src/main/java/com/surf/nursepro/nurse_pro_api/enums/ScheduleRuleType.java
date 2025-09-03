package com.surf.nursepro.nurse_pro_api.enums;

import lombok.Getter;

public enum ScheduleRuleType {
    coverage("coverage"), workload("workload"),
    preference("preference"), constraint("constraint");

    @Getter
    private final String value;

    ScheduleRuleType(String st) {
        this.value = st;
    }
}
