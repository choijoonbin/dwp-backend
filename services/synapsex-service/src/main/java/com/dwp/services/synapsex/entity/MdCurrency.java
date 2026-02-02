package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * md_currency — 통화 마스터(전역).
 */
@Entity
@Table(schema = "dwp_aura", name = "md_currency")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MdCurrency {

    @Id
    @Column(name = "currency_code", nullable = false, length = 5)
    private String currencyCode;

    @Column(name = "currency_name", nullable = false, columnDefinition = "TEXT")
    private String currencyName;

    @Column(name = "symbol", columnDefinition = "TEXT")
    private String symbol;

    @Column(name = "minor_unit")
    private Integer minorUnit;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
}
