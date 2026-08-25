-- Release governance evidence and the immutable active-pointer lineage are one
-- atomic fact. Bind exact bundle key, CAS revision, target bundle and operation
-- so an orphaned or contradictory release audit row cannot be written.

ALTER TABLE auth_product_authorization_activation_event
    ADD CONSTRAINT uk_product_authorization_activation_governance_target
        UNIQUE (bundle_key, resulting_revision, to_bundle_id, operation);

ALTER TABLE auth_product_authorization_governance_event
    ADD CONSTRAINT fk_product_authorization_governance_activation
        FOREIGN KEY (bundle_key, resulting_revision, bundle_id, operation)
        REFERENCES auth_product_authorization_activation_event(
            bundle_key, resulting_revision, to_bundle_id, operation);
