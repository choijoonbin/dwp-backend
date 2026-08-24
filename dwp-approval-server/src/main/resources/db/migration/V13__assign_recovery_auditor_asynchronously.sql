ALTER TABLE apr_integration_outbox
    ADD COLUMN recovery_auditor_assignment_state VARCHAR(24)
        NOT NULL DEFAULT 'LEGACY_UNASSIGNED',
    ADD COLUMN recovery_auditor_resource_set_key VARCHAR(120),
    ADD COLUMN recovery_auditor_assignment_revision VARCHAR(240),
    ADD COLUMN recovery_auditor_assigned_at TIMESTAMPTZ,
    ADD COLUMN recovery_auditor_assignment_epoch INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN recovery_auditor_assignment_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN recovery_auditor_assignment_available_at TIMESTAMPTZ
        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN recovery_auditor_assignment_locked_by VARCHAR(240),
    ADD COLUMN recovery_auditor_assignment_locked_until TIMESTAMPTZ,
    ADD COLUMN recovery_auditor_assignment_exhausted_at TIMESTAMPTZ,
    ADD COLUMN recovery_auditor_assignment_next_probe_at TIMESTAMPTZ,
    ADD COLUMN recovery_auditor_assignment_last_error VARCHAR(1000);

ALTER TABLE apr_integration_outbox
    ALTER COLUMN recovery_auditor_assignment_state SET DEFAULT 'PENDING';

ALTER TABLE apr_integration_outbox
    ADD CONSTRAINT ck_apr_recovery_assignment_state CHECK (
        recovery_auditor_assignment_state IN (
            'PENDING', 'ASSIGNING', 'RETRY', 'EXHAUSTED', 'NOT_REQUIRED',
            'ASSIGNED', 'LEGACY_UNASSIGNED')),
    ADD CONSTRAINT ck_apr_recovery_assignment_attempts CHECK (
        recovery_auditor_assignment_attempt_count >= 0),
    ADD CONSTRAINT ck_apr_recovery_assignment_epoch CHECK (
        recovery_auditor_assignment_epoch >= 1),
    ADD CONSTRAINT ck_apr_recovery_assignment_lease CHECK (
        (recovery_auditor_assignment_state = 'ASSIGNING'
            AND recovery_auditor_assignment_locked_by IS NOT NULL
            AND recovery_auditor_assignment_locked_until IS NOT NULL)
        OR (recovery_auditor_assignment_state IN (
                'PENDING', 'RETRY', 'EXHAUSTED', 'NOT_REQUIRED', 'ASSIGNED')
            AND recovery_auditor_assignment_locked_by IS NULL
            AND recovery_auditor_assignment_locked_until IS NULL)
        OR recovery_auditor_assignment_state = 'LEGACY_UNASSIGNED'),
    ADD CONSTRAINT ck_apr_recovery_assignment_evidence CHECK (
        (recovery_auditor_assignment_state = 'ASSIGNED'
            AND assigned_auditor_user_id IS NOT NULL
            AND recovery_auditor_resource_set_key = 'RS_APPROVALS'
            AND recovery_auditor_assignment_revision IS NOT NULL
            AND btrim(recovery_auditor_assignment_revision) <> ''
            AND recovery_auditor_assigned_at IS NOT NULL
            AND recovery_auditor_assignment_locked_by IS NULL
            AND recovery_auditor_assignment_locked_until IS NULL)
        OR (recovery_auditor_assignment_state IN (
                'PENDING', 'ASSIGNING', 'RETRY', 'EXHAUSTED', 'NOT_REQUIRED')
            AND assigned_auditor_user_id IS NULL
            AND recovery_auditor_resource_set_key IS NULL
            AND recovery_auditor_assignment_revision IS NULL
            AND recovery_auditor_assigned_at IS NULL)
        OR recovery_auditor_assignment_state = 'LEGACY_UNASSIGNED'),
    ADD CONSTRAINT ck_apr_recovery_assignment_probe_schedule CHECK (
        (recovery_auditor_assignment_state = 'EXHAUSTED'
            AND recovery_auditor_assignment_exhausted_at IS NOT NULL
            AND recovery_auditor_assignment_next_probe_at IS NOT NULL
            AND recovery_auditor_assignment_next_probe_at
                > recovery_auditor_assignment_exhausted_at)
        OR (recovery_auditor_assignment_state <> 'EXHAUSTED'
            AND recovery_auditor_assignment_exhausted_at IS NULL
            AND recovery_auditor_assignment_next_probe_at IS NULL));

CREATE INDEX idx_apr_recovery_auditor_assignment_claim
    ON apr_integration_outbox (
        recovery_auditor_assignment_available_at, created_at, outbox_id)
    WHERE recovery_auditor_assignment_state IN ('PENDING', 'RETRY', 'ASSIGNING');

CREATE INDEX idx_apr_recovery_auditor_assignment_probe
    ON apr_integration_outbox (
        recovery_auditor_assignment_next_probe_at, created_at, outbox_id)
    WHERE recovery_auditor_assignment_state = 'EXHAUSTED';

CREATE TABLE apr_recovery_auditor_assignment_events (
    assignment_event_id UUID PRIMARY KEY,
    outbox_id UUID NOT NULL REFERENCES apr_integration_outbox(outbox_id),
    tenant_id BIGINT NOT NULL,
    assignment_epoch INTEGER NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    attempt_count INTEGER NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    worker_id VARCHAR(240) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_apr_recovery_assignment_event_epoch CHECK (assignment_epoch >= 1),
    CONSTRAINT ck_apr_recovery_assignment_event_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_apr_recovery_assignment_event_type CHECK (
        event_type IN ('EPOCH_EXHAUSTED', 'AUTOMATIC_PROBE_EPOCH_OPENED'))
);

CREATE INDEX idx_apr_recovery_assignment_event_timeline
    ON apr_recovery_auditor_assignment_events (
        tenant_id, outbox_id, occurred_at, assignment_event_id);

CREATE FUNCTION reject_apr_recovery_assignment_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Recovery auditor assignment events are append-only';
END;
$$;

CREATE TRIGGER trg_apr_recovery_assignment_events_append_only
    BEFORE UPDATE OR DELETE ON apr_recovery_auditor_assignment_events
    FOR EACH ROW EXECUTE FUNCTION reject_apr_recovery_assignment_event_mutation();

COMMENT ON COLUMN apr_integration_outbox.recovery_auditor_assignment_state IS
    'Immutable async lifecycle. EXHAUSTED waits for cooldown; published NOT_REQUIRED is terminal.';

COMMENT ON COLUMN apr_integration_outbox.recovery_auditor_assignment_revision IS
    'Auth-issued revision proving deterministic auditor selection for this outbox event.';

COMMENT ON COLUMN apr_integration_outbox.recovery_auditor_assignment_available_at IS
    'Next bounded-backoff time at which an unavailable assignment may be retried.';

COMMENT ON COLUMN apr_integration_outbox.recovery_auditor_assignment_next_probe_at IS
    'Earliest server-only cooldown expiry for opening the next bounded retry epoch.';

COMMENT ON TABLE apr_recovery_auditor_assignment_events IS
    'Append-only evidence for exhausted epochs and cooldown-governed automatic probes.';
