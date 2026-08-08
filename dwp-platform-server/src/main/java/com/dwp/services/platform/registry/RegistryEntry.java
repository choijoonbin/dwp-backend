package com.dwp.services.platform.registry;

import com.dwp.core.entity.BaseEntity;
import com.dwp.services.platform.reference.ReferenceLifecycle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "adm_registry_entries",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"tenant_id", "registry_type", "entry_key", "revision"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistryEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "registry_entry_id")
    private Long registryEntryId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "registry_type", nullable = false, length = 24)
    private RegistryType registryType;

    @Column(name = "entry_key", nullable = false, length = 100)
    private String entryKey;

    @Column(name = "revision", nullable = false)
    private Integer revision;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "owner_ref", nullable = false, length = 160)
    private String ownerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tier", nullable = false, length = 20)
    private RiskTier riskTier;

    @Column(name = "artifact_version", nullable = false, length = 64)
    private String artifactVersion;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private ReferenceLifecycle lifecycleState = ReferenceLifecycle.DRAFT;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}

