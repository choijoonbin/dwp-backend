-- Declare the canonical combined v33 draft without changing immutable v32.
-- ProductAuthorizationSeedLoader remains the single importer. Flyway neither
-- imports nor approves, activates, or assigns this release to a tenant.
-- The combined HRIS closure introduces Payroll and Time-owned predicates, so
-- extend the owner allowlist before an operator can explicitly import v33.
ALTER TABLE auth_product_predicate_policy
    DROP CONSTRAINT IF EXISTS ck_product_predicate_owner_service;

ALTER TABLE auth_product_predicate_policy
    ADD CONSTRAINT ck_product_predicate_owner_service
    CHECK (owner_service_key IN (
        'agent',
        'approval',
        'auth',
        'meeting',
        'messaging',
        'notification',
        'payroll',
        'people',
        'platform',
        'space',
        'time'
    )) NOT VALID;

ALTER TABLE auth_product_predicate_policy
    VALIDATE CONSTRAINT ck_product_predicate_owner_service;

INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    33,
    '9c9a18b44eb83de0e98f4ec16e44c1df0ce216e00bc7075462f4e35f7fb87639',
    'product-surfaces-v1.bundle-v33.generated.json',
    'DRAFT',
    FALSE);
