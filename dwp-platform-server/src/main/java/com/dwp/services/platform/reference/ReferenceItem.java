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

import java.time.Instant;

@Entity
@Table(
        name = "adm_reference_items",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"tenant_id", "reference_set_id", "code"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reference_item_id")
    private Long referenceItemId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "reference_set_id", nullable = false)
    private Long referenceSetId;

    @Column(name = "code", nullable = false, length = 80)
    private String code;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private ReferenceLifecycle lifecycleState = ReferenceLifecycle.DRAFT;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "parent_code", length = 80)
    private String parentCode;

    @Column(name = "parent_reference_item_id")
    private Long parentReferenceItemId;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
