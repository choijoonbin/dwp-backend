-- V49 and V50 added migration-owner trigger functions after the original V5
-- hardening pass. Keep their trigger-only elevation explicit, path-pinned and
-- unavailable for direct invocation by PUBLIC or either service login role.
ALTER FUNCTION public.pay_compensation_plan_input_reject_mutation()
    SECURITY DEFINER;
ALTER FUNCTION public.pay_compensation_plan_input_reject_mutation()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION public.pay_v50_reject_mutation()
    SECURITY DEFINER;
ALTER FUNCTION public.pay_v50_reject_mutation()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION public.pay_v50_guard_run_revision()
    SECURITY DEFINER;
ALTER FUNCTION public.pay_v50_guard_run_revision()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION public.pay_v50_guard_attempt_transition()
    SECURITY DEFINER;
ALTER FUNCTION public.pay_v50_guard_attempt_transition()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION public.pay_v50_guard_snapshot_insert()
    SECURITY DEFINER;
ALTER FUNCTION public.pay_v50_guard_snapshot_insert()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION public.pay_v50_guard_snapshot_ref_insert()
    SECURITY DEFINER;
ALTER FUNCTION public.pay_v50_guard_snapshot_ref_insert()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION public.pay_v50_guard_worker_record()
    SECURITY DEFINER;
ALTER FUNCTION public.pay_v50_guard_worker_record()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION public.pay_v50_guard_receipt_record()
    SECURITY DEFINER;
ALTER FUNCTION public.pay_v50_guard_receipt_record()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION public.pay_v50_guard_calculation_fact_insert()
    SECURITY DEFINER;
ALTER FUNCTION public.pay_v50_guard_calculation_fact_insert()
    SET search_path TO pg_catalog, public, pg_temp;

ALTER FUNCTION public.pay_v50_guard_outbox_event()
    SECURITY DEFINER;
ALTER FUNCTION public.pay_v50_guard_outbox_event()
    SET search_path TO pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION
    public.pay_compensation_plan_input_reject_mutation(),
    public.pay_v50_reject_mutation(),
    public.pay_v50_guard_run_revision(),
    public.pay_v50_guard_attempt_transition(),
    public.pay_v50_guard_snapshot_insert(),
    public.pay_v50_guard_snapshot_ref_insert(),
    public.pay_v50_guard_worker_record(),
    public.pay_v50_guard_receipt_record(),
    public.pay_v50_guard_calculation_fact_insert(),
    public.pay_v50_guard_outbox_event()
FROM PUBLIC, "${payrollRuntimeRole}", "${payrollProjectionPublisherRole}";
