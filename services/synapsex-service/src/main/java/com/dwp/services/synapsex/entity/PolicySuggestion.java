package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * policy_suggestion — 정책 제안 (feedback 확장)
 */
@Entity
@Table(schema = "dwp_aura", name = "policy_suggestion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicySuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "suggestion_id")
    private Long suggestionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "suggested_action", length = 100)
    private String suggestedAction;

    @Column(name = "suggested_rule", columnDefinition = "TEXT")
    private String suggestedRule;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
