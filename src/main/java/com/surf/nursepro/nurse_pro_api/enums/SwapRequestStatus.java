package com.surf.nursepro.nurse_pro_api.enums;

import lombok.Getter;

public enum SwapRequestStatus {
    Pending("Pending"), Approved("Approved"), Rejected("Rejected");

    @Getter
    private final String value;

    SwapRequestStatus(String st) {
        this.value = st;
    }
}
