package com.dwp.services.platform.home.personalization;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "usr_home_composer_proposals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeComposerProposal extends HomePersonalizationEntity {
    @Id
    @Column(name = "proposal_id")
    private UUID proposalId;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "view_id", nullable = false)
    private UUID viewId;
    @Column(name = "state", nullable = false, length = 24)
    private String state;
    @Column(name = "base_view_version", nullable = false)
    private Long baseViewVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason_codes", nullable = false, columnDefinition = "jsonb")
    private JsonNode reasonCodes;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode changesPayload;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode warningsPayload;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_layout", nullable = false, columnDefinition = "jsonb")
    private JsonNode beforeLayout;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_layout", nullable = false, columnDefinition = "jsonb")
    private JsonNode proposedLayout;
    @Column(name = "applied_revision_id")
    private UUID appliedRevisionId;
    @Column(name = "undone_revision_id")
    private UUID undoneRevisionId;
    @Column(name = "creation_command_id", nullable = false)
    private UUID creationCommandId;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;
    @Column(name = "applied_view_version")
    private Long appliedViewVersion;
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
