package com.surf.nursepro.nurse_pro_api.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RescheduleMode {
    /** Keep every assignment that is still valid and only fill the gaps (shifts below their required staff). */
    @JsonProperty("fill_gaps")
    FILL_GAPS,

    /** Throw away the assignments in the range (except locked shifts) and generate them again. */
    @JsonProperty("rebuild_range")
    REBUILD_RANGE
}
