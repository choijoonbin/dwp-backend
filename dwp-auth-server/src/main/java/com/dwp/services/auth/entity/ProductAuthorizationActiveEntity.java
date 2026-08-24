package com.dwp.services.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "auth_product_authorization_active")
public class ProductAuthorizationActiveEntity {

    @Id
    @Column(name = "bundle_key", nullable = false, length = 80)
    private String bundleKey;

    @Column(name = "bundle_id", nullable = false, unique = true)
    private UUID bundleId;

    @Column(name = "revision", nullable = false)
    private Long revision;

    @Column(name = "activated_by", nullable = false, length = 160)
    private String activatedBy;

    @Column(name = "activated_at", nullable = false)
    private OffsetDateTime activatedAt;

    protected ProductAuthorizationActiveEntity() {
    }
}
