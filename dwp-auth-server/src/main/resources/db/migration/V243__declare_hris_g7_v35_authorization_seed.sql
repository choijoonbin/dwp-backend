-- Declare the canonical G7 HRIS People, Time, and Platform authorization wave.
-- ProductAuthorizationSeedLoader remains the single importer. Flyway neither
-- imports nor approves, activates, or assigns this release to a tenant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    35,
    '38ef33070a33b5da9a7825c764bd24daacd24c8ca4008f3a926727321d3d098d',
    'product-surfaces-v1.bundle-v35.generated.json',
    'DRAFT',
    FALSE);
