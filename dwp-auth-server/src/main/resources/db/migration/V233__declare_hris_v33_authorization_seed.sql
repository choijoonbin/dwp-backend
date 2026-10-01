-- Declare the immutable v33 draft that adds the exact HRIS system-management page contract.
-- ProductAuthorizationSeedLoader remains the single importer. Flyway does not import,
-- approve, activate, or assign this release to any tenant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    33,
    '254ead674e1126d50e8dcf1011486ea1127fb2479f7a82d832cdf0466995bc49',
    'product-surfaces-v1.bundle-v33.generated.json',
    'DRAFT',
    FALSE);
