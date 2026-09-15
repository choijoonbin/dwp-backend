package com.dwp.services.platform.widgetregistry;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "plt_widget_definition_versions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WidgetDefinitionVersion extends WidgetRegistryEntity {
    @Id
    @Column(name = "version_id", nullable = false)
    private UUID versionId;
    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;
    @Column(name = "semantic_version", nullable = false, length = 40)
    private String semanticVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest", nullable = false, columnDefinition = "jsonb")
    private JsonNode manifest;
    @Column(name = "manifest_hash", nullable = false, length = 64)
    private String manifestHash;
    @Column(name = "renderer_key", nullable = false, length = 120)
    private String rendererKey;
    @Column(name = "workflow_state", nullable = false, length = 20)
    private String workflowState;
    @Column(name = "release_state", nullable = false, length = 20)
    private String releaseState;
    @Column(name = "safety_state", nullable = false, length = 20)
    private String safetyState;
    @Column(name = "immutable", nullable = false)
    private boolean immutable;
    @Column(name = "predecessor_version_id")
    private UUID predecessorVersionId;
    @Column(name = "replacement_version_id")
    private UUID replacementVersionId;
    @Column(name = "validation_run_id")
    private UUID validationRunId;
    @Column(name = "approved_by")
    private Long approvedBy;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attestation", nullable = false, columnDefinition = "jsonb")
    private JsonNode attestation;
    @Column(name = "certification_status", nullable = false, length = 20)
    private String certificationStatus;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
