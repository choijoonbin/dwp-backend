-- Declare the append-only SYS-009 v44 authorization carrier as an immutable
-- DRAFT seed only. Flyway does not import, approve, activate, assign, or grant
-- any controlled-transfer resource, role, package, duty, menu, or tenant access.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    44,
    '8b4e05e5974a8a25fb9edf318bb3cd609105f2e6353677b69c2eab1cd7038a1b',
    'product-surfaces-v1.bundle-v44.generated.json',
    'DRAFT',
    FALSE);
