package com.surf.nursepro.nurse_pro_api.enums;

import lombok.Getter;

public enum ScheduleStatus {
    Draft("Draft"), Published("Published"), Archived("Archived");

    @Getter
    private final String value;

    ScheduleStatus(String st) {
        this.value = st;
    }
}
