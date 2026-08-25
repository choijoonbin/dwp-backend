-- Make the provider approval, three-party release separation and activation
-- change reference database invariants as well as service invariants. This
-- protects the immutable audit ledger from future repository or privileged
-- writer mistakes without creating any automatic lifecycle transition.

CREATE OR REPLACE FUNCTION dwp_guard_product_authorization_governance_event()
RETURNS TRIGGER AS $$
DECLARE
    stored_status VARCHAR(20);
    stored_approver VARCHAR(160);
    stored_approved_at TIMESTAMPTZ;
    approval_requester VARCHAR(160);
    approval_actor VARCHAR(160);
    approval_change_ref VARCHAR(160);
BEGIN
    SELECT bundle_status, approved_by, approved_at
      INTO stored_status, stored_approver, stored_approved_at
      FROM auth_product_authorization_bundle
     WHERE bundle_id = NEW.bundle_id
       AND bundle_key = NEW.bundle_key
       AND version = NEW.version
       AND checksum = NEW.checksum;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'governance evidence requires an exact immutable authorization bundle';
    END IF;

    IF NEW.operation = 'APPROVE' THEN
        IF stored_status NOT IN ('APPROVED', 'ACTIVE')
           OR stored_approver IS NULL
           OR lower(stored_approver) <> lower(NEW.decision_actor_ref)
           OR stored_approved_at IS NULL
           OR stored_approved_at IS DISTINCT FROM NEW.occurred_at THEN
            RAISE EXCEPTION 'provider governance approval must match stored approval evidence';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.operation NOT IN ('ACTIVATE', 'ROLLBACK') THEN
        RETURN NEW;
    END IF;
    IF stored_status <> 'ACTIVE' OR stored_approver IS NULL OR stored_approved_at IS NULL THEN
        RAISE EXCEPTION 'release governance target must be the exact active approved bundle';
    END IF;

    SELECT requester_ref, decision_actor_ref, change_ref
      INTO approval_requester, approval_actor, approval_change_ref
      FROM auth_product_authorization_governance_event
     WHERE bundle_id = NEW.bundle_id
       AND bundle_key = NEW.bundle_key
       AND version = NEW.version
       AND checksum = NEW.checksum
       AND operation = 'APPROVE'
       AND caller_service_identity = 'dwp-provider-server';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'release governance requires exact provider approval evidence';
    END IF;
    IF lower(stored_approver) <> lower(approval_actor) THEN
        RAISE EXCEPTION 'stored approver must match provider governance approval evidence';
    END IF;
    IF NEW.requester_ref <> approval_requester THEN
        RAISE EXCEPTION 'release governance must reuse the provider approval requester';
    END IF;
    IF lower(NEW.decision_actor_ref) IN (
            lower(approval_requester), lower(approval_actor)) THEN
        RAISE EXCEPTION 'release governance requires requester, approver and release actor separation';
    END IF;
    IF NEW.operation = 'ACTIVATE' AND NEW.change_ref <> approval_change_ref THEN
        RAISE EXCEPTION 'activation governance must reuse the provider approval change reference';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_authorization_governance_evidence_guard
    BEFORE INSERT ON auth_product_authorization_governance_event
    FOR EACH ROW EXECUTE FUNCTION dwp_guard_product_authorization_governance_event();
