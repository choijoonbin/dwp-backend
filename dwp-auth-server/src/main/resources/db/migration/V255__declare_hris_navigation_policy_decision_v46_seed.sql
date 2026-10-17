-- Declare the append-only R6-B navigation POLICY v46 authorization carrier as
-- an immutable DRAFT seed only. Flyway does not import, approve, activate,
-- assign, grant, enroll, publish, or enable navigation for any tenant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    46,
    '3499e37653587feed433f84a79fbe7d0e80a1a7c0634d858a13f4e09d4bd015e',
    'product-surfaces-v1.bundle-v46.generated.json',
    'DRAFT',
    FALSE);
