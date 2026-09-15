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
@Table(name = "plt_widget_renderer_bindings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WidgetRendererBinding {
    @Id
    @Column(name = "renderer_binding_id", nullable = false)
    private UUID rendererBindingId;
    @Column(name = "renderer_key", nullable = false, length = 120)
    private String rendererKey;
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;
    @Column(name = "owner_product_key", nullable = false, length = 120)
    private String ownerProductKey;
    @Column(name = "source_app_resource_key", nullable = false, length = 120)
    private String sourceAppResourceKey;
    @Column(name = "minimum_host_api_version", nullable = false)
    private Integer minimumHostApiVersion;
    @Column(name = "maximum_host_api_version", nullable = false)
    private Integer maximumHostApiVersion;
    @Column(name = "binding_state", nullable = false, length = 16)
    private String bindingState;
    @Column(name = "binding_revision", nullable = false, length = 64)
    private String bindingRevision;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
}
