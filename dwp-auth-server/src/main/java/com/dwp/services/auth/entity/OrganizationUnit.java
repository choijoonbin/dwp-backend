package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "com_organization_units")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationUnit extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "org_unit_id")
    private Long orgUnitId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "org_key", nullable = false, length = 100)
    private String orgKey;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "parent_org_unit_id")
    private Long parentOrgUnitId;

    @Builder.Default
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType = "LOCAL";

    @Column(name = "external_id", length = 255)
    private String externalId;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Builder.Default
    @Column(nullable = false)
    private Long revision = 1L;

    @Version
    @Column(nullable = false)
    private Long version;
}
