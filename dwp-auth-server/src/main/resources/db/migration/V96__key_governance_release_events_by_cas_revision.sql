-- A governed activation always reuses the original approval change reference.
-- Therefore change_ref cannot identify a release transition: after an atomic
-- rollback, reactivating the same still-approved bundle is a new CAS revision
-- with the same approval reference. Key release evidence by the active-pointer
-- lineage revision instead, while V94 continues to allow one approval per bundle.

ALTER TABLE auth_product_authorization_governance_event
    DROP CONSTRAINT uk_product_authorization_governance_change;

CREATE UNIQUE INDEX ux_product_authorization_governance_release_revision
    ON auth_product_authorization_governance_event(bundle_key, resulting_revision)
    WHERE operation IN ('ACTIVATE', 'ROLLBACK');
