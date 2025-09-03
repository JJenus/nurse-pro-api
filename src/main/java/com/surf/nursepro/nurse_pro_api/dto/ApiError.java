package com.surf.nursepro.nurse_pro_api.dto;

import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@EqualsAndHashCode
public class ApiError {
    private String message;
    private String code;
    private Object details;
}