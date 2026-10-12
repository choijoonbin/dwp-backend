-- Append the corrective G7 HRIS authorization release without mutating the
-- sealed v35 seed. ProductAuthorizationSeedLoader remains the single importer;
-- Flyway neither imports nor approves, activates, or assigns this release.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    36,
    '12e4b9329ab53ab1efd6c5f6e23ccc7869c4184a79e5a64f614fe9c40a9e704b',
    'product-surfaces-v1.bundle-v36.generated.json',
    'DRAFT',
    FALSE);
