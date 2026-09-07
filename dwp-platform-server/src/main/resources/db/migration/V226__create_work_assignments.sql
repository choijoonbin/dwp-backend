-- Human-confirmed assignments are independent Work records, never reassigned personal tasks.
CREATE TABLE work_assignments (
    assignment_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    created_by_user_id BIGINT NOT NULL CHECK (created_by_user_id > 0),
    assigned_by_user_id BIGINT NOT NULL CHECK (assigned_by_user_id > 0),
    assignee_user_id BIGINT NOT NULL CHECK (assignee_user_id > 0),
    source_system VARCHAR(32) NOT NULL CHECK (source_system = 'MEETING_FOLLOWUP'),
    meeting_id UUID NOT NULL,
    report_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    confirmed_source_version BIGINT NOT NULL CHECK (confirmed_source_version >= 0),
    title VARCHAR(500) NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 500),
    description VARCHAR(4000),
    priority VARCHAR(10) NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    due_at TIMESTAMPTZ,
    assignment_state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (assignment_state IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    work_state VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (work_state IN ('OPEN', 'IN_PROGRESS', 'WAITING', 'COMPLETED', 'CANCELLED')),
    assignment_revision BIGINT NOT NULL DEFAULT 0 CHECK (assignment_revision >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= assignment_revision),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_work_assignment_tenant UNIQUE (tenant_id, assignment_id),
    -- Cancellation and report rebinding never make the same candidate promotable again.
    CONSTRAINT uk_work_assignment_candidate UNIQUE (tenant_id, source_system, candidate_id),
    CONSTRAINT ck_work_assignment_lifecycle CHECK (
        assignment_state = 'ACCEPTED' OR work_state IN ('OPEN', 'CANCELLED')),
    CONSTRAINT ck_work_assignment_acceptance CHECK (
        (assignment_state = 'ACCEPTED' AND accepted_at IS NOT NULL)
        OR (assignment_state <> 'ACCEPTED' AND accepted_at IS NULL)),
    CONSTRAINT ck_work_assignment_completion CHECK (
        (work_state = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (work_state <> 'COMPLETED' AND completed_at IS NULL))
);
CREATE INDEX idx_work_assignment_assignee
    ON work_assignments (tenant_id, assignee_user_id, updated_at DESC, assignment_id);
CREATE INDEX idx_work_assignment_creator
    ON work_assignments (tenant_id, created_by_user_id, updated_at DESC, assignment_id);

CREATE TABLE work_assignment_events (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    assignment_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL
        CHECK (action IN ('CREATE', 'ACCEPT', 'DECLINE', 'START', 'WAIT', 'COMPLETE', 'CANCEL', 'REASSIGN')),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    assignee_user_id BIGINT NOT NULL CHECK (assignee_user_id > 0),
    assignment_state VARCHAR(16) NOT NULL CHECK (assignment_state IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    work_state VARCHAR(16) NOT NULL CHECK (work_state IN ('OPEN', 'IN_PROGRESS', 'WAITING', 'COMPLETED', 'CANCELLED')),
    assignment_revision BIGINT NOT NULL CHECK (assignment_revision >= 0),
    version BIGINT NOT NULL CHECK (version >= assignment_revision),
    reason_code VARCHAR(48),
    audit_record_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, assignment_id) REFERENCES work_assignments (tenant_id, assignment_id),
    UNIQUE (tenant_id, assignment_id, version),
    CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{2,47}$'),
    CHECK (action NOT IN ('DECLINE', 'CANCEL', 'REASSIGN') OR reason_code IS NOT NULL)
);

-- A receipt is command evidence, not a cached copy of sensitive task/source content.
-- Returning it always requires the caller to remain a current Work participant.
CREATE TABLE work_assignment_command_receipts (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    command_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    operation VARCHAR(16) NOT NULL
        CHECK (operation IN ('CREATE', 'ACCEPT', 'DECLINE', 'START', 'WAIT', 'COMPLETE', 'CANCEL', 'REASSIGN')),
    target_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    applied_version BIGINT NOT NULL CHECK (applied_version >= 0),
    applied_assignment_revision BIGINT NOT NULL CHECK (applied_assignment_revision >= 0),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_user_id, command_id),
    FOREIGN KEY (tenant_id, assignment_id) REFERENCES work_assignments (tenant_id, assignment_id)
);

CREATE FUNCTION protect_work_assignment_identity() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.assignment_id, NEW.tenant_id, NEW.created_by_user_id, NEW.source_system,
           NEW.meeting_id, NEW.report_id, NEW.candidate_id, NEW.confirmed_source_version)
       IS DISTINCT FROM
       ROW(OLD.assignment_id, OLD.tenant_id, OLD.created_by_user_id, OLD.source_system,
           OLD.meeting_id, OLD.report_id, OLD.candidate_id, OLD.confirmed_source_version) THEN
        RAISE EXCEPTION 'work assignment ownership and source identity are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_work_assignment_identity
    BEFORE UPDATE ON work_assignments
    FOR EACH ROW EXECUTE FUNCTION protect_work_assignment_identity();

CREATE FUNCTION reject_work_assignment_evidence_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'work assignment evidence is append-only';
END;
$$;
CREATE TRIGGER trg_work_assignment_events_append_only
    BEFORE UPDATE OR DELETE ON work_assignment_events
    FOR EACH ROW EXECUTE FUNCTION reject_work_assignment_evidence_mutation();
CREATE TRIGGER trg_work_assignment_receipts_append_only
    BEFORE UPDATE OR DELETE ON work_assignment_command_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_work_assignment_evidence_mutation();
