-- Declare the immutable TIM-007 product-authorization v50 carrier only.
-- It remains DRAFT and default-off: Flyway does not import, approve, activate,
-- assign, grant, enroll, publish, or enable this release for any tenant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
        'product-surfaces',
        50,
        'c52222caad2749c4048c694f6582e157f6ab1184f73237f0944c3d88807a2760',
        'product-surfaces-v1.bundle-v50.generated.json',
        'DRAFT',
        FALSE
);
