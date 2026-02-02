package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** 한도/정책(계정·카테고리·코스트센터 등). dwp_aura.rule_threshold */
@Entity
@Table(schema = "dwp_aura", name = "rule_threshold")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "threshold_id")
    private Long thresholdId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Column(name = "policy_doc_id")
    private String policyDocId;

    @Column(name = "dimension", nullable = false)
    private String dimension;

    @Column(name = "dimension_key", nullable = false)
    private String dimensionKey;

    @Column(name = "waers", nullable = false)
    @Builder.Default
    private String waers = "KRW";

    @Column(name = "threshold_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal thresholdAmount;

    @Column(name = "require_evidence", nullable = false)
    @Builder.Default
    private Boolean requireEvidence = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_types", columnDefinition = "jsonb")
    private Map<String, Object> evidenceTypes;

    @Column(name = "severity_on_breach", nullable = false)
    @Builder.Default
    private String severityOnBreach = "MEDIUM";

    @Column(name = "action_on_breach", nullable = false)
    @Builder.Default
    private String actionOnBreach = "FLAG_FOR_REVIEW";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

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
