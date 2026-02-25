package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * case_explanation — 사용자 소명/증빙 기록
 */
@Entity
@Table(schema = "dwp_aura", name = "case_explanation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseExplanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "explanation_id")
    private Long explanationId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "explanation_text", nullable = false, columnDefinition = "TEXT")
    private String explanationText;

    @Column(name = "evidence_attachment_id", length = 255)
    private String evidenceAttachmentId;

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
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
