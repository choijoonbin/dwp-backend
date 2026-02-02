package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * PII 정책(필드별 저장 정책). dwp_aura.policy_pii_field
 */
@Entity
@Table(schema = "dwp_aura", name = "policy_pii_field")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyPiiField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pii_id")
    private Long piiId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Column(name = "field_name", nullable = false)
    private String fieldName; // fieldKey in API

    @Column(name = "handling", nullable = false)
    private String handling; // ALLOW | MASK | HASH_ONLY | ENCRYPT | FORBID

    @Column(name = "note")
    private String note;

    @Column(name = "mask_rule")
    private String maskRule;

    @Column(name = "hash_rule")
    private String hashRule;

    @Column(name = "encrypt_rule")
    private String encryptRule;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", insertable = false, updatable = false)
    private ConfigProfile configProfile;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
