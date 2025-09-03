package com.surf.nursepro.nurse_pro_api.enums;

import lombok.Getter;

public enum ScheduleConflictType {
    understaffed("understaffed"), overstaffed("overstaffed"), qualification("qualification"),
    availability("availability"), overtime("overtime");

    @Getter
    private final String value;

    ScheduleConflictType(String st) {
        this.value = st;
    }
}
