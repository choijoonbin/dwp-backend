package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * tenant_company_code_scope — 회사코드(BUKRS) 스코프.
 */
@Entity
@Table(schema = "dwp_aura", name = "tenant_company_code_scope")
@IdClass(TenantCompanyCodeScopeId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantCompanyCodeScope {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Id
    @Column(name = "bukrs", nullable = false, length = 4)
    private String bukrs;

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    @Column(name = "source", nullable = false, length = 16)
    @Builder.Default
    private String source = "MANUAL";

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
