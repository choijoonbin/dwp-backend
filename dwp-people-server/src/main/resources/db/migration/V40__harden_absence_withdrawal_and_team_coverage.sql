ALTER TABLE abs_leave_requests
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by BIGINT,
    ADD COLUMN cancellation_note VARCHAR(1000);

UPDATE abs_leave_requests
   SET cancelled_at = COALESCE(decided_at, updated_at, CURRENT_TIMESTAMP),
       cancelled_by = COALESCE(decided_by, updated_by),
       cancellation_note = COALESCE(decision_note, 'Migrated cancellation evidence')
 WHERE status = 'CANCELLED';

ALTER TABLE abs_leave_requests
    ADD CONSTRAINT ck_abs_leave_request_cancellation_evidence
    CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL);

CREATE INDEX idx_abs_leave_requests_team_coverage
    ON abs_leave_requests (tenant_id, start_at, end_at, worker_id)
    WHERE status IN ('SUBMITTED', 'APPROVED');

COMMENT ON COLUMN abs_leave_requests.cancellation_note IS
    'Requester withdrawal evidence. Approved leave cancellation remains a policy-owned workflow.';
COMMENT ON COLUMN abs_leave_requests.cancelled_at IS
    'Timestamp when a submitted request was withdrawn by its requester.';
