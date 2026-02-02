package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 데이터 보호 정책 (암호화, 보존기간, 내보내기 제어). dwp_aura.policy_data_protection
 */
@Entity
@Table(schema = "dwp_aura", name = "policy_data_protection")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyDataProtection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "protection_id")
    private Long protectionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Column(name = "at_rest_encryption_enabled", nullable = false)
    @Builder.Default
    private Boolean atRestEncryptionEnabled = false;

    @Column(name = "key_provider", nullable = false, length = 20)
    @Builder.Default
    private String keyProvider = "KMS_MOCK";

    @Column(name = "kms_mode", nullable = false, length = 32)
    @Builder.Default
    private String kmsMode = "KMS_MANAGED_KEYS";

    @Column(name = "audit_retention_years", nullable = false)
    @Builder.Default
    private Integer auditRetentionYears = 7;

    @Column(name = "export_requires_approval", nullable = false)
    @Builder.Default
    private Boolean exportRequiresApproval = true;

    @Column(name = "export_mode", nullable = false, length = 20)
    @Builder.Default
    private String exportMode = "ZIP";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", insertable = false, updatable = false)
    private ConfigProfile configProfile;

    @PrePersist
    @PreUpdate
    public void prePersist() {
        if (updatedAt == null) updatedAt = Instant.now();
    }
}
