package com.surf.nursepro.nurse_pro_api.enums;

import lombok.Getter;

public enum ExperienceLevel {
    Junior("Junior"), Mid("Mid"), Senior("Senior"), Expert("Expert");

    @Getter
    private final String value;

    ExperienceLevel(String st) {
        this.value = st;
    }
}
