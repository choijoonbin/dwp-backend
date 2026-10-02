-- Trigger functions cross from least-privilege runtime/publisher DML into
-- migration-owner invariants. Keep that elevation explicit, path-pinned, and
-- unavailable for direct invocation by PUBLIC or service login roles.
ALTER FUNCTION pay_foundation_guard_receipt_transition()
    SECURITY DEFINER;
ALTER FUNCTION pay_foundation_guard_receipt_transition()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION pay_guard_legal_entity_scope_projection_transition()
    SECURITY DEFINER;
ALTER FUNCTION pay_guard_legal_entity_scope_projection_transition()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION pay_guard_legal_entity_scope_member_lifecycle()
    SECURITY DEFINER;
ALTER FUNCTION pay_guard_legal_entity_scope_member_lifecycle()
    SET search_path TO pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION pay_foundation_guard_receipt_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_guard_legal_entity_scope_projection_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_guard_legal_entity_scope_member_lifecycle() FROM PUBLIC;
