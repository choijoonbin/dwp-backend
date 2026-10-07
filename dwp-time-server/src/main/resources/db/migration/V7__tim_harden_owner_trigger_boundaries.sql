-- Normalize every post-foundation trigger entry point to the single owner
-- execution profile enforced at runtime. Trigger execution remains available;
-- direct function execution remains revoked from service and publisher roles.
ALTER FUNCTION tim_reject_target_evidence_mutation()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION tim_reject_command_authority_evidence_mutation()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION tim_guard_work_regime_sod_evidence()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION tim_reject_runtime_projection_update()
    SET search_path TO pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION tim_reject_target_evidence_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION tim_reject_command_authority_evidence_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION tim_guard_work_regime_sod_evidence() FROM PUBLIC;
REVOKE ALL ON FUNCTION tim_reject_runtime_projection_update() FROM PUBLIC;
