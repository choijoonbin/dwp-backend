-- PostgreSQL CHECK constraints accept UNKNOWN, so the original arithmetic
-- equality alone did not reject a NULL resulting revision. Keep the immutable
-- ledger intact and make both CAS revisions explicitly mandatory for release
-- operations.

ALTER TABLE auth_product_authorization_governance_event
    DROP CONSTRAINT ck_product_authorization_governance_revision;

ALTER TABLE auth_product_authorization_governance_event
    ADD CONSTRAINT ck_product_authorization_governance_revision
        CHECK ((operation = 'APPROVE'
                    AND expected_revision IS NULL
                    AND resulting_revision IS NULL)
            OR (operation IN ('ACTIVATE', 'ROLLBACK')
                    AND expected_revision IS NOT NULL
                    AND resulting_revision IS NOT NULL
                    AND expected_revision >= 0
                    AND resulting_revision = expected_revision + 1));
