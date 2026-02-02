package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * policy_sod_rule — Profile별 SoD 규칙. NO_SELF_APPROVE, DUAL_CONTROL, FINANCE_VS_SECURITY 등.
 */
@Entity
@Table(schema = "dwp_aura", name = "policy_sod_rule")
@IdClass(PolicySodRuleId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicySodRule {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Id
    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Id
    @Column(name = "rule_key", nullable = false, columnDefinition = "TEXT")
    private String ruleKey;

    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String description = "";

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    @Column(name = "severity", nullable = false, length = 16)
    @Builder.Default
    private String severity = "WARN";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private JsonNode configJson = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    @PreUpdate
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
}
