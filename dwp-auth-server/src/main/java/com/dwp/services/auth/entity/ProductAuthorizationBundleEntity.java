package com.dwp.services.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "auth_product_authorization_bundle")
public class ProductAuthorizationBundleEntity {

    @Id
    @Column(name = "bundle_id", nullable = false)
    private UUID bundleId;

    @Column(name = "bundle_key", nullable = false, length = 80)
    private String bundleKey;

    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "bundle_status", nullable = false, length = 20)
    private String bundleStatus;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(name = "checksum_algorithm", nullable = false, length = 20)
    private String checksumAlgorithm;

    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    @Column(name = "owner", nullable = false, length = 200)
    private String owner;

    @Column(name = "approved_by", length = 160)
    private String approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ProductAuthorizationBundleEntity() {
    }
}
