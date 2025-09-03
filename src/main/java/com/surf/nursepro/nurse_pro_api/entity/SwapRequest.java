package com.surf.nursepro.nurse_pro_api.entity;

import com.surf.nursepro.nurse_pro_api.enums.SwapRequestStatus;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "swap_requests")
public class SwapRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String requesterId;
    private String targetId;
    private String shiftId;
    private String targetShiftId;
    private String reason;

    @Enumerated(EnumType.STRING)
    private SwapRequestStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String reviewedBy;
    private String reviewNotes;
    private boolean autoMatched;
}