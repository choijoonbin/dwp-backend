package com.dwp.services.platform.widgetregistry;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "plt_widget_runtime_enable_approvals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WidgetRuntimeEnableApproval {
    @Id
    @Column(name = "approval_id", nullable = false)
    private UUID approvalId;
    @Column(name = "control_id", nullable = false)
    private UUID controlId;
    @Column(name = "control_revision", nullable = false)
    private Long controlRevision;
    @Column(name = "approval_state", nullable = false, length = 16)
    private String approvalState;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs", nullable = false, columnDefinition = "jsonb")
    private JsonNode evidenceRefs;
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
    @Column(name = "approved_by", nullable = false)
    private Long approvedBy;
    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;
    @Column(name = "consumed_by_command_id")
    private UUID consumedByCommandId;
}
