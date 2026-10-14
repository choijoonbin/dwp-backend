-- Declare the immutable compensation-planning authorization release. The
-- ProductAuthorizationSeedLoader remains the sole importer: Flyway never
-- approves, activates, or assigns a tenant to this release.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    39,
    '86e796c95e45c02892d4c4ae76e7a5b36114ab09554ffc1be3a450066fe8e34c',
    'product-surfaces-v1.bundle-v39.generated.json',
    'DRAFT',
    FALSE);
