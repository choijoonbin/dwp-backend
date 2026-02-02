package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * tenant_currency_scope — 통화(WAERS) 스코프.
 */
@Entity
@Table(schema = "dwp_aura", name = "tenant_currency_scope")
@IdClass(TenantCurrencyScopeId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantCurrencyScope {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Id
    @Column(name = "waers", nullable = false, length = 5)
    private String waers;

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    @Column(name = "fx_control_mode", nullable = false, length = 16)
    @Builder.Default
    private String fxControlMode = "ALLOW";

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
