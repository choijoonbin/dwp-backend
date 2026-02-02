package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * tenant_scope_seed_state — Idempotent 시드 완료 상태.
 */
@Entity
@Table(schema = "dwp_aura", name = "tenant_scope_seed_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantScopeSeedState {

    @Id
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "seeded_at", nullable = false)
    private Instant seededAt;

    @Column(name = "seed_version", nullable = false, length = 16)
    @Builder.Default
    private String seedVersion = "v1";

    @PrePersist
    public void prePersist() {
        if (seededAt == null) seededAt = Instant.now();
    }
}
