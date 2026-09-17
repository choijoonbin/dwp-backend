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
@Table(name = "adm_tenant_widget_policy_heads")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantWidgetPolicyHead {
    @Id
    @Column(name = "policy_head_id", nullable = false)
    private UUID policyHeadId;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;
    @Column(name = "current_revision_id")
    private UUID currentRevisionId;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    @Column(name = "updated_by")
    private Long updatedBy;
}
