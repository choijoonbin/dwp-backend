package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "plt_widget_registry_state")
@Getter
@Setter
@NoArgsConstructor
public class WidgetRegistryState {
    @Id
    @Column(name = "environment", nullable = false, length = 24)
    private String environment;
    @Column(name = "migration_mode", nullable = false, length = 16)
    private String migrationMode;
    @Column(name = "runtime_activation_ready", nullable = false)
    private boolean runtimeActivationReady;
    @Column(name = "registry_revision", nullable = false)
    private Long registryRevision;
    @Column(name = "policy_revision", nullable = false)
    private Long policyRevision;
    @Column(name = "safety_revision", nullable = false)
    private Long safetyRevision;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
