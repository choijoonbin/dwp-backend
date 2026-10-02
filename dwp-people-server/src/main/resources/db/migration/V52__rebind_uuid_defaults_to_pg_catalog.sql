-- Keep runtime UUID generation on PostgreSQL's trusted built-in routine.
-- Flyway can set search_path to only the default schema, which caused older
-- unqualified defaults to bind to public.pgcrypto instead of pg_catalog.
SET LOCAL search_path = pg_catalog;

ALTER TABLE public.abs_leave_plans
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.abs_leave_requests
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.abs_mail_proposal_execution_receipts
    ALTER COLUMN receipt_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.bnf_benefit_plans
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.bnf_benefit_programs
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.bnf_enrollment_windows
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.bnf_enrollments
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.int_connector_instances
    ALTER COLUMN connector_instance_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.int_mapping_profiles
    ALTER COLUMN mapping_profile_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.int_reconciliation_issues
    ALTER COLUMN reconciliation_issue_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.int_reconciliation_runs
    ALTER COLUMN reconciliation_run_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.int_sync_runs
    ALTER COLUMN sync_run_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.pay_pay_cycles
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.pay_statement_references
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_assignment_change_reason_catalog
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_assignments
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_organization_role_assignments
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_organization_role_catalog
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_organization_scenario_approvals
    ALTER COLUMN organization_scenario_approval_id
        SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_organization_scenario_changes
    ALTER COLUMN organization_scenario_change_id
        SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_organization_scenario_validation_runs
    ALTER COLUMN organization_scenario_validation_run_id
        SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_organization_scenarios
    ALTER COLUMN organization_scenario_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_organization_type_catalog
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_organizations
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_persons
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_position_relationships
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_positions
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_work_relationships
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_workers
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.ppl_workforce_access_policies
    ALTER COLUMN workforce_access_policy_id
        SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.sys_people_audit_events
    ALTER COLUMN audit_event_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.sys_people_outbox_events
    ALTER COLUMN event_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.tal_goals
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.tal_journey_instances
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.tal_journey_templates
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.tal_learning_assignments
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.tme_time_cards
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.tme_time_entries
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.tme_time_exceptions
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();
ALTER TABLE public.tme_work_schedule_profiles
    ALTER COLUMN public_id SET DEFAULT pg_catalog.gen_random_uuid();

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pg_catalog.pg_attrdef default_value
          JOIN pg_catalog.pg_class relation
            ON relation.oid = default_value.adrelid
          JOIN pg_catalog.pg_namespace table_namespace
            ON table_namespace.oid = relation.relnamespace
          JOIN pg_catalog.pg_depend dependency
            ON dependency.classid = 'pg_attrdef'::pg_catalog.regclass
           AND dependency.objid = default_value.oid
           AND dependency.refclassid = 'pg_proc'::pg_catalog.regclass
         WHERE table_namespace.nspname = 'public'
           AND dependency.refobjid =
               pg_catalog.to_regprocedure('public.gen_random_uuid()')
    ) THEN
        RAISE EXCEPTION
            'People UUID defaults still depend on public.gen_random_uuid()';
    END IF;
END
$migration$;
