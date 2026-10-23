package com.surf.nursepro.nurse_pro_api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Whether a swap request could be approved right now, and why not. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SwapCheckResult {
    private boolean canApprove;
    private String status;
    private List<String> problems = new ArrayList<>();
}
