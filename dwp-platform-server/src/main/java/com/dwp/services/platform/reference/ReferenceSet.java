package com.dwp.services.platform.reference;

import com.dwp.core.entity.BaseEntity;
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
        name = "adm_reference_sets",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "set_key"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceSet extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reference_set_id")
    private Long referenceSetId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "set_key", nullable = false, length = 80)
    private String setKey;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private ReferenceLifecycle lifecycleState = ReferenceLifecycle.DRAFT;

    @Builder.Default
    @Column(name = "content_revision", nullable = false)
    private Long contentRevision = 1L;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
