package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * case_comment — 케이스 코멘트
 */
@Entity
@Table(schema = "dwp_aura", name = "case_comment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "author_user_id")
    private Long authorUserId;

    @Column(name = "author_agent_id", length = 80)
    private String authorAgentId;

    @Column(name = "comment_text", nullable = false, columnDefinition = "TEXT")
    private String commentText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
