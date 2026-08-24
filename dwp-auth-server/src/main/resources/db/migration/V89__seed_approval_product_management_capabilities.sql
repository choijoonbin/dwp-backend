-- The descriptor rows remain single-sourced by the generated immutable v2 seed
-- and ProductAuthorizationSeedLoader. This release manifest is deliberately
-- default-off: Flyway never imports, approves, or activates the bundle.

CREATE TABLE auth_product_authorization_seed_release (
    bundle_key VARCHAR(80) NOT NULL,
    version BIGINT NOT NULL,
    checksum CHAR(64) NOT NULL,
    auth_seed_artifact VARCHAR(240) NOT NULL,
    intended_bundle_status VARCHAR(20) NOT NULL,
    automatic_import_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    declared_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (bundle_key, version),
    CONSTRAINT ck_product_authorization_seed_release_version CHECK (version > 0),
    CONSTRAINT ck_product_authorization_seed_release_checksum
        CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_product_authorization_seed_release_status
        CHECK (intended_bundle_status = 'DRAFT'),
    CONSTRAINT ck_product_authorization_seed_release_default_off
        CHECK (automatic_import_enabled = FALSE)
);

INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    2,
    '5b634a35472ef98ecdd5ca9efe7a716020d8f3ae0d8f5025d76bbf072692c12c',
    'product-surfaces-v1.bundle-v2.generated.json',
    'DRAFT',
    FALSE);

CREATE TRIGGER trg_product_authorization_seed_release_immutable
    BEFORE UPDATE OR DELETE ON auth_product_authorization_seed_release
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();

COMMENT ON TABLE auth_product_authorization_seed_release IS
    'Operational manifest for generated DRAFT seeds; never an activation or descriptor source.';
