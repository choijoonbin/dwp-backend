-- Close durable SoD evidence rewrites and allow the runtime to take row locks without granting it
-- authority to provision or mutate target-population projections.

CREATE OR REPLACE FUNCTION tim_guard_work_regime_sod_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF NEW.author_actor_id IS DISTINCT FROM OLD.author_actor_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'work-regime author and creation evidence are immutable';
    END IF;

    IF OLD.lifecycle_state = 'IN_REVIEW' AND NEW.lifecycle_state = 'APPROVED' THEN
        IF NEW.published_by_actor_id IS DISTINCT FROM OLD.published_by_actor_id
           OR NEW.publication_receipt_public_id IS DISTINCT FROM OLD.publication_receipt_public_id
           OR OLD.approval_actor_id IS NOT NULL
           OR OLD.approval_receipt_public_id IS NOT NULL
           OR NEW.approval_actor_id IS NULL
           OR NEW.approval_receipt_public_id IS NULL THEN
            RAISE EXCEPTION 'only approval evidence may be established by approval transition';
        END IF;
    ELSIF OLD.lifecycle_state = 'APPROVED' AND NEW.lifecycle_state = 'PUBLISHED' THEN
        IF NEW.approval_actor_id IS DISTINCT FROM OLD.approval_actor_id
           OR NEW.approval_receipt_public_id IS DISTINCT FROM OLD.approval_receipt_public_id
           OR OLD.published_by_actor_id IS NOT NULL
           OR OLD.publication_receipt_public_id IS NOT NULL
           OR NEW.published_by_actor_id IS NULL
           OR NEW.publication_receipt_public_id IS NULL THEN
            RAISE EXCEPTION 'approval evidence is immutable and only publisher evidence may be established';
        END IF;
    ELSIF NEW.approval_actor_id IS DISTINCT FROM OLD.approval_actor_id
          OR NEW.approval_receipt_public_id IS DISTINCT FROM OLD.approval_receipt_public_id
          OR NEW.published_by_actor_id IS DISTINCT FROM OLD.published_by_actor_id
          OR NEW.publication_receipt_public_id IS DISTINCT FROM OLD.publication_receipt_public_id THEN
        RAISE EXCEPTION 'work-regime SoD evidence may change only in its governed transition';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_tim_guard_work_regime_sod_evidence
    BEFORE UPDATE ON tim_work_regime_versions
    FOR EACH ROW EXECUTE FUNCTION tim_guard_work_regime_sod_evidence();

CREATE OR REPLACE FUNCTION tim_reject_runtime_projection_update()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF session_user = '${timeRuntimeRole}'
       OR current_setting('role', true) = '${timeRuntimeRole}' THEN
        RAISE EXCEPTION 'TIM runtime cannot mutate target-population authority projections';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_tim_reject_runtime_population_update
    BEFORE UPDATE ON tim_target_population_projections
    FOR EACH ROW EXECUTE FUNCTION tim_reject_runtime_projection_update();
CREATE TRIGGER trg_tim_reject_runtime_actor_grant_update
    BEFORE UPDATE ON tim_target_population_actor_grants
    FOR EACH ROW EXECUTE FUNCTION tim_reject_runtime_projection_update();
CREATE TRIGGER trg_tim_reject_runtime_member_update
    BEFORE UPDATE ON tim_target_population_members
    FOR EACH ROW EXECUTE FUNCTION tim_reject_runtime_projection_update();

-- PostgreSQL row-locking SELECTs require UPDATE privilege on at least one column. Only the
-- non-authoritative timestamp column is granted, and the trigger above rejects even that write for
-- the runtime role. Projection publishers retain their existing owner privileges.
GRANT UPDATE (updated_at) ON TABLE
    tim_target_population_projections,
    tim_target_population_actor_grants,
    tim_target_population_members
TO "${timeRuntimeRole}";

REVOKE ALL ON FUNCTION tim_guard_work_regime_sod_evidence() FROM PUBLIC;
REVOKE ALL ON FUNCTION tim_reject_runtime_projection_update() FROM PUBLIC;
