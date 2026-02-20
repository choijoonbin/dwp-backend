package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * agent_document_mapping — 에이전트-문서 매핑 (복합키: agent_id, doc_id)
 */
@Entity
@Table(schema = "dwp_aura", name = "agent_document_mapping")
@IdClass(AgentDocumentMappingId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentDocumentMapping {

    @Id
    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Id
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

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
