package com.surf.nursepro.nurse_pro_api.enums;

import lombok.Getter;

public enum Role {
    USER("USER"), ADMIN("ADMIN");

    @Getter
    private final String value;

    Role(String st) {
        this.value = st;
    }
}
