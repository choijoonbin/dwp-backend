package com.dwp.services.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ProductAuthorizationEntityId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "bundle_id", nullable = false)
    private UUID bundleId;

    @Column(name = "contract_key", nullable = false)
    private String contractKey;

    protected ProductAuthorizationEntityId() {
    }

    public ProductAuthorizationEntityId(UUID bundleId, String contractKey) {
        this.bundleId = Objects.requireNonNull(bundleId);
        this.contractKey = Objects.requireNonNull(contractKey);
    }

    public UUID getBundleId() {
        return bundleId;
    }

    public String getContractKey() {
        return contractKey;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ProductAuthorizationEntityId that)) return false;
        return bundleId.equals(that.bundleId) && contractKey.equals(that.contractKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bundleId, contractKey);
    }
}
