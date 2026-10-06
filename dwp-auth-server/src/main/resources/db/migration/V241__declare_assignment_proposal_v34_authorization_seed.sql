-- Declare the canonical assignment-proposal v34 draft without changing immutable v33.
-- ProductAuthorizationSeedLoader remains the single importer. Flyway neither
-- imports nor approves, activates, or assigns this release to a tenant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    34,
    '852d20e1e639e1a7170f02b5714d21d8c51a9eb8ff5ac32d8b7940b82d6be83b',
    'product-surfaces-v1.bundle-v34.generated.json',
    'DRAFT',
    FALSE);
