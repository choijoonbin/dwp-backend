-- Close the People audit-evidence owner/runtime boundary without rewriting history.
--
-- This is intentionally a forward-only hardening migration. Existing audit and
-- outbox rows remain untouched. Replacing routine attributes and recreating the
-- named trigger make the DDL safe for an existing V50 database, including one
-- where an interrupted/manual candidate left the trigger behind.

ALTER FUNCTION public.sys_people_audit_to_outbox() SECURITY DEFINER;
ALTER FUNCTION public.sys_people_audit_to_outbox()
    SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.sys_people_audit_to_outbox() FROM PUBLIC;

CREATE OR REPLACE FUNCTION public.sys_reject_people_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION 'sys_people_audit_events is append-only';
END;
$function$;

REVOKE ALL ON FUNCTION public.sys_reject_people_audit_event_mutation() FROM PUBLIC;

DROP TRIGGER IF EXISTS trg_sys_people_audit_events_append_only
    ON public.sys_people_audit_events;
CREATE TRIGGER trg_sys_people_audit_events_append_only
    BEFORE UPDATE OR DELETE ON public.sys_people_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION public.sys_reject_people_audit_event_mutation();

-- The application runtime is provisioned outside Flyway. Explicit PUBLIC
-- revocation guarantees a subsequently-created runtime role cannot inherit
-- mutation or direct trigger-routine authority.
REVOKE UPDATE, DELETE ON TABLE public.sys_people_audit_events FROM PUBLIC;

-- These pre-existing append-only triggers are in the same protected schema and
-- are evaluated by the common owner-trigger boundary guard. Normalize them here
-- so the production database, rather than a test fixture, owns the full contract.
ALTER FUNCTION public.prevent_ppl_org_scenario_validation_mutation()
    SECURITY DEFINER;
ALTER FUNCTION public.prevent_ppl_org_scenario_validation_mutation()
    SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.prevent_ppl_org_scenario_validation_mutation()
    FROM PUBLIC;

ALTER FUNCTION public.reject_workforce_export_attempt_mutation()
    SECURITY DEFINER;
ALTER FUNCTION public.reject_workforce_export_attempt_mutation()
    SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.reject_workforce_export_attempt_mutation()
    FROM PUBLIC;

COMMENT ON FUNCTION public.sys_people_audit_to_outbox() IS
    'Owner-executed audit outbox projection with a fixed trusted search path; direct PUBLIC execution is denied.';
COMMENT ON FUNCTION public.sys_reject_people_audit_event_mutation() IS
    'Owner-executed invariant guard that rejects UPDATE and DELETE against append-only People audit evidence.';
