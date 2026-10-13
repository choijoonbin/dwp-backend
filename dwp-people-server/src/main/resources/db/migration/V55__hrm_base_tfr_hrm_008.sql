-- BASE-TFR-HRM-008: canonical effective-dated employment/assignment lifecycle.
-- The V53 proposal aggregate remains proposal evidence. Canonical employment
-- commands use ppl_assignment_events and the shared ppl_command_receipts ledger.
SET LOCAL search_path = pg_catalog;

ALTER TABLE public.ppl_job_profiles
    ADD COLUMN public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    ADD CONSTRAINT uk_ppl_job_profiles_public_id UNIQUE (public_id),
    ADD CONSTRAINT uk_ppl_job_profiles_tenant_public_id UNIQUE (tenant_id, public_id);

ALTER TABLE public.ppl_locations
    ADD COLUMN public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    ADD COLUMN calendar_public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    ADD CONSTRAINT uk_ppl_locations_public_id UNIQUE (public_id),
    ADD CONSTRAINT uk_ppl_locations_tenant_public_id UNIQUE (tenant_id, public_id),
    ADD CONSTRAINT uk_ppl_locations_calendar_public_id UNIQUE (calendar_public_id);

CREATE TABLE public.ppl_assignment_events (
    assignment_event_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES public.sys_service_tenants(tenant_id),
    subject_person_id BIGINT NOT NULL,
    target_worker_id BIGINT,
    published_work_relationship_id BIGINT,
    published_assignment_id BIGINT,
    event_type VARCHAR(40) NOT NULL,
    lifecycle_state VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    effective_date DATE NOT NULL,
    command_context JSONB NOT NULL,
    event_payload JSONB NOT NULL,
    input_snapshot_hash CHAR(64) NOT NULL,
    expected_owner_version BIGINT NOT NULL,
    impact_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    impact_snapshot_hash CHAR(64),
    approval_request_id UUID,
    approval_decision_event_id UUID,
    approval_decision_revision BIGINT,
    approval_payload_hash CHAR(64),
    correlation_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    validated_at TIMESTAMPTZ,
    validated_by BIGINT,
    submitted_at TIMESTAMPTZ,
    submitted_by BIGINT,
    approved_at TIMESTAMPTZ,
    approved_by_principal UUID,
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_assignment_events_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_assignment_events_id UNIQUE (tenant_id, assignment_event_id),
    CONSTRAINT uk_ppl_assignment_events_correlation UNIQUE (tenant_id, correlation_id),
    CONSTRAINT uk_ppl_assignment_events_approval UNIQUE (tenant_id, approval_request_id),
    CONSTRAINT fk_ppl_assignment_events_person FOREIGN KEY (tenant_id, subject_person_id)
        REFERENCES public.ppl_persons(tenant_id, person_id),
    CONSTRAINT fk_ppl_assignment_events_worker FOREIGN KEY (tenant_id, target_worker_id)
        REFERENCES public.ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_ppl_assignment_events_relationship
        FOREIGN KEY (tenant_id, published_work_relationship_id)
        REFERENCES public.ppl_work_relationships(tenant_id, work_relationship_id),
    CONSTRAINT fk_ppl_assignment_events_assignment
        FOREIGN KEY (tenant_id, published_assignment_id)
        REFERENCES public.ppl_assignments(tenant_id, assignment_id),
    CONSTRAINT ck_ppl_assignment_events_type CHECK (
        event_type IN ('HIRE', 'CONCURRENT_EMPLOYMENT')),
    CONSTRAINT ck_ppl_assignment_events_state CHECK (
        lifecycle_state IN ('DRAFT', 'VALIDATED', 'PENDING_APPROVAL',
                            'APPROVED', 'PUBLISHED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_ppl_assignment_events_json CHECK (
        jsonb_typeof(command_context) = 'object'
        AND jsonb_typeof(event_payload) = 'object'
        AND jsonb_typeof(impact_snapshot) = 'object'),
    CONSTRAINT ck_ppl_assignment_events_hashes CHECK (
        input_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND (impact_snapshot_hash IS NULL
             OR impact_snapshot_hash ~ '^[0-9a-f]{64}$')
        AND (approval_payload_hash IS NULL
             OR approval_payload_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_ppl_assignment_events_versions CHECK (
        expected_owner_version >= 0 AND version >= 0
        AND (approval_decision_revision IS NULL OR approval_decision_revision > 0)),
    CONSTRAINT ck_ppl_assignment_events_validation CHECK (
        lifecycle_state = 'DRAFT'
        OR (validated_at IS NOT NULL AND impact_snapshot_hash IS NOT NULL)),
    CONSTRAINT ck_ppl_assignment_events_submission CHECK (
        lifecycle_state NOT IN ('PENDING_APPROVAL', 'APPROVED', 'PUBLISHED')
        OR (submitted_at IS NOT NULL AND approval_request_id IS NOT NULL)),
    CONSTRAINT ck_ppl_assignment_events_approval CHECK (
        lifecycle_state NOT IN ('APPROVED', 'PUBLISHED')
        OR (approval_decision_event_id IS NOT NULL
            AND approval_decision_revision IS NOT NULL
            AND approval_payload_hash IS NOT NULL
            AND approved_at IS NOT NULL
            AND approved_by_principal IS NOT NULL)),
    CONSTRAINT ck_ppl_assignment_events_publication CHECK (
        lifecycle_state <> 'PUBLISHED'
        OR (published_at IS NOT NULL AND published_by IS NOT NULL
            AND target_worker_id IS NOT NULL
            AND published_work_relationship_id IS NOT NULL
            AND published_assignment_id IS NOT NULL))
);

CREATE INDEX idx_ppl_assignment_events_subject
    ON public.ppl_assignment_events(
        tenant_id, subject_person_id, lifecycle_state, effective_date DESC);
CREATE INDEX idx_ppl_assignment_events_queue
    ON public.ppl_assignment_events(
        tenant_id, lifecycle_state, effective_date, created_at);

CREATE TABLE public.ppl_assignment_event_approval_decisions (
    approval_decision_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.sys_service_tenants(tenant_id),
    assignment_event_id BIGINT NOT NULL,
    approval_request_id UUID NOT NULL,
    decision_event_id UUID NOT NULL,
    decision VARCHAR(16) NOT NULL,
    decision_actor_public_id UUID NOT NULL,
    approval_policy_revision BIGINT NOT NULL,
    expected_event_version BIGINT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CONSTRAINT uk_ppl_assignment_event_decision UNIQUE (tenant_id, decision_event_id),
    CONSTRAINT uk_ppl_assignment_event_request
        UNIQUE (tenant_id, assignment_event_id, approval_request_id),
    CONSTRAINT fk_ppl_assignment_event_decision_event
        FOREIGN KEY (tenant_id, assignment_event_id)
        REFERENCES public.ppl_assignment_events(tenant_id, assignment_event_id),
    CONSTRAINT ck_ppl_assignment_event_decision_value CHECK (
        decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_ppl_assignment_event_decision_versions CHECK (
        approval_policy_revision > 0 AND expected_event_version >= 0),
    CONSTRAINT ck_ppl_assignment_event_decision_hash CHECK (
        payload_sha256 ~ '^[0-9a-f]{64}$')
);

-- Proposal rows stay intent evidence; this optional one-way reference is the
-- adapter handoff and never makes proposal-local events the publication ledger.
ALTER TABLE public.ppl_assignment_change_proposals
    ADD COLUMN canonical_assignment_event_id BIGINT,
    ADD CONSTRAINT fk_ppl_assignment_proposal_canonical_event
        FOREIGN KEY (tenant_id, canonical_assignment_event_id)
        REFERENCES public.ppl_assignment_events(tenant_id, assignment_event_id);

CREATE UNIQUE INDEX uk_ppl_assignment_proposal_canonical_event
    ON public.ppl_assignment_change_proposals(tenant_id, canonical_assignment_event_id)
    WHERE canonical_assignment_event_id IS NOT NULL;

CREATE UNIQUE INDEX uk_ppl_assignment_event_semantic_outbox
    ON public.sys_people_outbox_events(tenant_id, aggregate_type, aggregate_id, event_type)
    WHERE aggregate_type = 'EMPLOYMENT_EVENT'
      AND event_type IN ('AssignmentChanged.v1', 'EmploymentChanged.v1',
                         'WorkerChanged.v1', 'WorkerHired.v1',
                         'WorkplaceAssignmentChanged.v1');

CREATE FUNCTION public.protect_ppl_assignment_event()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRM assignment events are forward-corrected, not deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('PUBLISHED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION 'HRM terminal assignment event is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.assignment_event_id, NEW.public_id, NEW.tenant_id,
           NEW.subject_person_id, NEW.event_type, NEW.effective_date,
           NEW.command_context, NEW.event_payload, NEW.input_snapshot_hash,
           NEW.expected_owner_version, NEW.correlation_id,
           NEW.created_at, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.assignment_event_id, OLD.public_id, OLD.tenant_id,
           OLD.subject_person_id, OLD.event_type, OLD.effective_date,
           OLD.command_context, OLD.event_payload, OLD.input_snapshot_hash,
           OLD.expected_owner_version, OLD.correlation_id,
           OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION 'HRM assignment event source evidence is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('APPROVED', 'PUBLISHED')
       AND ROW(NEW.approval_request_id, NEW.approval_decision_event_id,
               NEW.approval_decision_revision, NEW.approval_payload_hash,
               NEW.approved_at, NEW.approved_by_principal)
           IS DISTINCT FROM
           ROW(OLD.approval_request_id, OLD.approval_decision_event_id,
               OLD.approval_decision_revision, OLD.approval_payload_hash,
               OLD.approved_at, OLD.approved_by_principal) THEN
        RAISE EXCEPTION 'HRM assignment approval evidence is immutable'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.protect_ppl_assignment_event() FROM PUBLIC;

CREATE TRIGGER trg_ppl_assignment_event_guard
    BEFORE UPDATE OR DELETE ON public.ppl_assignment_events
    FOR EACH ROW EXECUTE FUNCTION public.protect_ppl_assignment_event();

ALTER TABLE public.ppl_assignment_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ppl_assignment_events FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_assignment_events_tenant_policy
    ON public.ppl_assignment_events
    USING (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE public.ppl_assignment_event_approval_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ppl_assignment_event_approval_decisions FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_assignment_event_approval_decisions_tenant_policy
    ON public.ppl_assignment_event_approval_decisions
    USING (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

COMMENT ON TABLE public.ppl_assignment_events IS
    'Canonical typed HIRE and CONCURRENT_EMPLOYMENT event aggregate and publication evidence.';
COMMENT ON COLUMN public.ppl_assignment_change_proposals.canonical_assignment_event_id IS
    'Optional adapter reference; proposal rows remain non-ledger intent evidence.';
COMMENT ON TABLE public.ppl_assignment_event_approval_decisions IS
    'Replay-fenced Approval-owner callbacks for assignment-event publication.';
