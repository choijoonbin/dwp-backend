package com.dwp.services.platform.widgetregistry;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
@Table(name = "adm_tenant_widget_policy_revisions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantWidgetPolicyRevision {
    @Id
    @Column(name = "policy_revision_id", nullable = false)
    private UUID policyRevisionId;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;
    @Column(name = "revision_number", nullable = false)
    private Long revisionNumber;
    @Column(name = "policy_state", nullable = false, length = 16)
    private String policyState;
    @Column(name = "enabled", nullable = false)
    private boolean enabled;
    @Column(name = "selector_type", nullable = false, length = 16)
    private String selectorType;
    @Column(name = "channel", length = 16)
    private String channel;
    @Column(name = "version_id")
    private UUID versionId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_surface_keys", nullable = false, columnDefinition = "jsonb")
    private JsonNode supportedSurfaceKeys;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_selector", nullable = false, columnDefinition = "jsonb")
    private JsonNode audienceSelector;
    @Column(name = "required_widget", nullable = false)
    private boolean requiredWidget;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "locked_configuration", nullable = false, columnDefinition = "jsonb")
    private JsonNode lockedConfiguration;
    @Column(name = "sharing_policy", nullable = false, length = 16)
    private String sharingPolicy;
    @Column(name = "impact_revision", length = 64)
    private String impactRevision;
    @Column(name = "predecessor_revision_id")
    private UUID predecessorRevisionId;
    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;
    @Column(name = "reason_text", nullable = false, length = 500)
    private String reasonText;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    @Column(name = "created_by")
    private Long createdBy;
}
