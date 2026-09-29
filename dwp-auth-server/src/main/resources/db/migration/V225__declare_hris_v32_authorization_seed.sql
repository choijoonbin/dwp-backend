-- Record the immutable v32 seed that closes the first HRIS Wave 1 owner APIs.
-- ProductAuthorizationSeedLoader remains the single importer, and governance
-- approval plus activation stay explicit; Flyway only declares the artifact.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    32,
    '9e4e274bf457d1a5947c8b54e83299d28fb9fe128d9f1100991bc30634b54344',
    'product-surfaces-v1.bundle-v32.generated.json',
    'DRAFT',
    FALSE);
