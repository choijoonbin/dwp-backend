-- The review-operation capture helper changes the transaction-local tenant context and is
-- callable only by the migration-owned trigger path. PostgreSQL grants EXECUTE to PUBLIC by
-- default, which would otherwise let an application role choose an arbitrary tenant before
-- accessing the FORCE-RLS projections.
REVOKE ALL ON FUNCTION hris_performance.prf_capture_review_operation(BIGINT, UUID)
    FROM PUBLIC;

-- Bind history inserts to the nested parent-trigger call stack. The marker alone is not trusted:
-- the history guard also requires trigger depth two and the effective migration-owner identity.
CREATE OR REPLACE FUNCTION hris_performance.prf_capture_review_operation_trigger()
RETURNS TRIGGER AS $$
DECLARE
    target_tenant_id BIGINT;
    target_cycle_id UUID;
    previous_capture_path TEXT;
BEGIN
    target_tenant_id := NEW.tenant_id;
    IF TG_TABLE_NAME = 'prf_cycles' THEN
        target_cycle_id := NEW.cycle_id;
    ELSIF TG_TABLE_NAME = 'prf_cycle_versions' THEN
        target_cycle_id := NEW.cycle_id;
    ELSIF TG_TABLE_NAME = 'prf_population_freezes' THEN
        target_cycle_id := NEW.cycle_id;
    ELSE
        SELECT cycle_id INTO target_cycle_id
          FROM hris_performance.prf_cycle_versions
         WHERE tenant_id = NEW.tenant_id
           AND cycle_version_id = NEW.cycle_version_id;
    END IF;

    previous_capture_path := pg_catalog.current_setting(
        'dwp.prf_review_operation_capture_path', TRUE);
    PERFORM pg_catalog.set_config(
        'dwp.prf_review_operation_capture_path', 'PARENT_TRIGGER', TRUE);
    BEGIN
        PERFORM hris_performance.prf_capture_review_operation(
            target_tenant_id, target_cycle_id);
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config(
            'dwp.prf_review_operation_capture_path',
            COALESCE(previous_capture_path, ''), TRUE);
        RAISE;
    END;
    PERFORM pg_catalog.set_config(
        'dwp.prf_review_operation_capture_path',
        COALESCE(previous_capture_path, ''), TRUE);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- All trigger entry points execute only with the migration owner and a fixed catalog-only search
-- path. The ordinary capture helper remains SECURITY INVOKER and is reachable solely through the
-- migration-owned trigger function after its PUBLIC execute privilege is revoked above.
ALTER FUNCTION hris_performance.prf_serialize_population_preview()
    SECURITY DEFINER;
ALTER FUNCTION hris_performance.prf_serialize_population_preview()
    SET search_path TO pg_catalog;

ALTER FUNCTION hris_performance.prf_capture_review_operation_trigger()
    SECURITY DEFINER;
ALTER FUNCTION hris_performance.prf_capture_review_operation_trigger()
    SET search_path TO pg_catalog;

ALTER FUNCTION hris_performance.prf_reject_population_freeze_mutation()
    SECURITY DEFINER;
ALTER FUNCTION hris_performance.prf_reject_population_freeze_mutation()
    SET search_path TO pg_catalog;

CREATE FUNCTION hris_performance.prf_guard_review_operation_history()
RETURNS TRIGGER AS $$
DECLARE
    capture_owner TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'PER_REVIEW_OPERATION_HISTORY_IMMUTABLE'
            USING ERRCODE = '55000';
    END IF;

    SELECT pg_catalog.pg_get_userbyid(routine.proowner)
      INTO capture_owner
      FROM pg_catalog.pg_proc routine
      JOIN pg_catalog.pg_namespace namespace
        ON namespace.oid = routine.pronamespace
     WHERE namespace.nspname = 'hris_performance'
       AND routine.proname = 'prf_capture_review_operation_trigger'
       AND pg_catalog.pg_get_function_identity_arguments(routine.oid) = '';

    IF capture_owner IS NULL
       OR CURRENT_USER::TEXT IS DISTINCT FROM capture_owner
       OR pg_catalog.pg_trigger_depth() < 2
       OR pg_catalog.current_setting(
              'dwp.prf_review_operation_capture_path', TRUE)
              IS DISTINCT FROM 'PARENT_TRIGGER' THEN
        RAISE EXCEPTION 'PER_REVIEW_OPERATION_HISTORY_OWNER_PATH_REQUIRED'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog;

CREATE TRIGGER trg_prf_review_operation_history_append_only
    BEFORE INSERT OR UPDATE OR DELETE
    ON hris_performance.prf_review_operation_history
    FOR EACH ROW EXECUTE FUNCTION hris_performance.prf_guard_review_operation_history();

-- Trigger invocation does not require service-role EXECUTE. Seal every owner entry point from
-- direct PUBLIC/runtime invocation; the migration owner retains the implicit owner privilege.
REVOKE ALL ON FUNCTION hris_performance.prf_serialize_population_preview()
    FROM PUBLIC;
REVOKE ALL ON FUNCTION hris_performance.prf_capture_review_operation_trigger()
    FROM PUBLIC;
REVOKE ALL ON FUNCTION hris_performance.prf_reject_population_freeze_mutation()
    FROM PUBLIC;
REVOKE ALL ON FUNCTION hris_performance.prf_guard_review_operation_history()
    FROM PUBLIC;
