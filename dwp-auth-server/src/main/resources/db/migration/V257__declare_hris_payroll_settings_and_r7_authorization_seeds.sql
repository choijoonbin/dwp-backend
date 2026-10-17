-- Close the append-only declaration lineage for the immutable v48 payroll/settings
-- carrier and declare the R7 central product-authorization v49 carrier.  Both
-- remain DRAFT and default-off: Flyway does not import, approve, activate,
-- assign, grant, enroll, publish, or enable either release for any tenant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES
    (
        'product-surfaces',
        48,
        '9fd324cd67ede1f02cc8ac33c08ad880ec6e283f99f5d7aa50e0e514545aa043',
        'product-surfaces-v1.bundle-v48.generated.json',
        'DRAFT',
        FALSE
    ),
    (
        'product-surfaces',
        49,
        '5c1dccdc27d8b260fbf001de5695e983a7107072824a8087038340d0d84cbc60',
        'product-surfaces-v1.bundle-v49.generated.json',
        'DRAFT',
        FALSE
    );
