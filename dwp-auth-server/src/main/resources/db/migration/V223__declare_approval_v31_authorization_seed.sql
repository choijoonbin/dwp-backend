-- Record the immutable v31 seed that closes every public Approval owner route.
-- ProductAuthorizationSeedLoader remains the single importer, and governance
-- approval plus activation stay explicit; Flyway only declares the artifact.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    31,
    'be4e1b6db3d3f0b5100182a3c80066a39c64479f9ba88d908fee661efd3335b8',
    'product-surfaces-v1.bundle-v31.generated.json',
    'DRAFT',
    FALSE);
