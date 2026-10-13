-- Append the Wave 2 HRIS People, Time, and Payroll authorization release
-- without mutating any sealed v1-v37 seed. ProductAuthorizationSeedLoader
-- remains the single importer; Flyway neither imports nor approves, activates,
-- or assigns this release to a tenant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    38,
    'a41145d6a2faefd2e6030a2ca9a070769019d4db5513ea214bf542dda31c374a',
    'product-surfaces-v1.bundle-v38.generated.json',
    'DRAFT',
    FALSE);
