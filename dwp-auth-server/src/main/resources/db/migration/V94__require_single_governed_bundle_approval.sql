-- One immutable bundle can receive exactly one provider-governed approval.
-- Activation and rollback may occur more than once across incident recovery,
-- but the original maker/checker evidence cannot be replaced or duplicated.

CREATE UNIQUE INDEX ux_product_authorization_single_governed_approval
    ON auth_product_authorization_governance_event(bundle_id)
    WHERE operation = 'APPROVE';
