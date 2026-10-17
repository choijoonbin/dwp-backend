-- Declare the append-only R6-C canonical PAGE and runtime-owner-overlay v47
-- authorization carrier as an immutable DRAFT seed only. Flyway does not
-- import, approve, activate, assign, grant, enroll, publish, or enable it for
-- any tenant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    47,
    '925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e',
    'product-surfaces-v1.bundle-v47.generated.json',
    'DRAFT',
    FALSE);
