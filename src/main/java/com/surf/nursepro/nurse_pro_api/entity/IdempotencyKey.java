package com.surf.nursepro.nurse_pro_api.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Remembers the outcome of a request carrying an Idempotency-Key header so retries return the same result. Used from Phase 4. */
@Data
@Entity
@Table(name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_scope_key", columnNames = {"scope", "key_value"}))
public class IdempotencyKey {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 64)
    private String scope;

    @Column(name = "key_value", nullable = false, length = 128)
    private String keyValue;

    @Column(length = 128)
    private String requestHash;

    private String resourceId;

    private Integer responseStatus;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
