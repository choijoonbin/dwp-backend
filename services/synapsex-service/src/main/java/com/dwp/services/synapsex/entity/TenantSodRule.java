package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * tenant_sod_rule — SoD(Segregation of Duties) 규칙.
 */
@Entity
@Table(schema = "dwp_aura", name = "tenant_sod_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantSodRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "rule_key", nullable = false, length = 64)
    private String ruleKey;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    @Column(name = "severity", nullable = false, length = 16)
    @Builder.Default
    private String severity = "WARN";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applies_to", nullable = false, columnDefinition = "jsonb")
    private JsonNode appliesTo;

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
