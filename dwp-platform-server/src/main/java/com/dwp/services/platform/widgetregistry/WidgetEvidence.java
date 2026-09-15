package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "plt_widget_evidence")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WidgetEvidence {
    @Id
    @Column(name = "evidence_id", nullable = false)
    private UUID evidenceId;
    @Column(name = "version_id", nullable = false)
    private UUID versionId;
    @Column(name = "evidence_type", nullable = false, length = 24)
    private String evidenceType;
    @Column(name = "evidence_status", nullable = false, length = 16)
    private String evidenceStatus;
    @Column(name = "manifest_hash", nullable = false, length = 64)
    private String manifestHash;
    @Column(name = "evidence_ref", nullable = false, length = 256)
    private String evidenceRef;
    @Column(name = "evidence_sha256", nullable = false, length = 64)
    private String evidenceSha256;
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;
    @Column(name = "decision_revision", nullable = false)
    private Long decisionRevision;
    @Column(name = "waived_evidence_id")
    private UUID waivedEvidenceId;
    @Column(name = "tracking_ticket_ref", length = 128)
    private String trackingTicketRef;
    @Column(name = "reviewed_by", nullable = false)
    private Long reviewedBy;
    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
}
