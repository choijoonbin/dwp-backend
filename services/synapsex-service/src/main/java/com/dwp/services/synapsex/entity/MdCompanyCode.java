package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * md_company_code — 회사코드(BUKRS) 마스터. 표시명 등 SoT.
 */
@Entity
@Table(schema = "dwp_aura", name = "md_company_code")
@IdClass(MdCompanyCodeId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MdCompanyCode {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Id
    @Column(name = "bukrs", nullable = false, length = 4)
    private String bukrs;

    @Column(name = "bukrs_name", nullable = false, columnDefinition = "TEXT")
    private String bukrsName;

    @Column(name = "country", length = 3)
    private String country;

    @Column(name = "default_currency", length = 5)
    private String defaultCurrency;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "source_system", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String sourceSystem = "SAP";

    @Column(name = "last_sync_ts")
    private Instant lastSyncTs;

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
