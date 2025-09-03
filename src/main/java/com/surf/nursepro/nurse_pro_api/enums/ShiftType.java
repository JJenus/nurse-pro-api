package com.surf.nursepro.nurse_pro_api.enums;

import lombok.Getter;

public enum ShiftType {
    Day("Day"), Evening("Evening"), Night("Night");

    @Getter
    private final String value;

    ShiftType(String st) {
        this.value = st;
    }
}
