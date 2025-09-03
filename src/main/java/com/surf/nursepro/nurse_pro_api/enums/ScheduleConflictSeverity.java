package com.surf.nursepro.nurse_pro_api.enums;

import lombok.Getter;

public enum ScheduleConflictSeverity {
    low("low"), medium("medium"), high("high"), critical("critical");

    @Getter
    private final String value;

    ScheduleConflictSeverity(String st) {
        this.value = st;
    }
}
