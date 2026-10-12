-- Append the corrective G7 HRIS authorization release without mutating the
-- sealed v36 seed. ProductAuthorizationSeedLoader remains the single importer;
-- Flyway neither imports nor approves, activates, or assigns this release.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    37,
    '1f23280116bc5b29f1a7ea6cc4ccbd2092a91673979d6bb46164a0dbb95ae10c',
    'product-surfaces-v1.bundle-v37.generated.json',
    'DRAFT',
    FALSE);
