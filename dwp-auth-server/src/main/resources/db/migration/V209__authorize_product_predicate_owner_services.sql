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
        'people',
        'platform',
        'space'
    )) NOT VALID;

ALTER TABLE auth_product_predicate_policy
    VALIDATE CONSTRAINT ck_product_predicate_owner_service;
