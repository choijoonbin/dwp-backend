-- Declare the append-only R5 navigation v45 authorization carrier as an
-- immutable DRAFT seed only. Flyway does not import, approve, activate,
-- assign, grant, enroll, publish, or enable navigation for any tenant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    45,
    '6f41e17159104cd0fd721a13877611b6225bd6458cb2831f0f30e51573992e64',
    'product-surfaces-v1.bundle-v45.generated.json',
    'DRAFT',
    FALSE);
