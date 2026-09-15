package com.dwp.services.platform.widgetregistry;

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

@Entity
@Table(name = "plt_widget_runtime_controls")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WidgetRuntimeControl {
    @Id
    @Column(name = "control_id", nullable = false)
    private UUID controlId;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "provider_product_key", length = 120)
    private String providerProductKey;
    @Column(name = "control_scope", nullable = false, length = 24)
    private String controlScope;
    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;
    @Column(name = "target_id", length = 160)
    private String targetId;
    @Column(name = "control_state", nullable = false, length = 16)
    private String controlState;
    @Column(name = "control_revision", nullable = false)
    private Long controlRevision;
    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;
    @Column(name = "reason_text", nullable = false, length = 500)
    private String reasonText;
    @Column(name = "incident_ref", length = 128)
    private String incidentRef;
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;
    @Column(name = "predecessor_control_id")
    private UUID predecessorControlId;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;
}
