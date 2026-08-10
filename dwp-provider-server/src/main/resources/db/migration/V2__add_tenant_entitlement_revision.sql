ALTER TABLE prv_tenants
    ADD COLUMN entitlement_revision BIGINT NOT NULL DEFAULT 0;
