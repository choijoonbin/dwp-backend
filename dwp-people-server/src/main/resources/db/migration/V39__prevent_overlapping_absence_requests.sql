-- Absence eligibility and balances are policy data, but an employee cannot be
-- simultaneously absent through two active requests. The half-open range lets
-- one request end exactly when the next begins.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE abs_leave_requests
    ADD CONSTRAINT ex_abs_leave_request_active_overlap
    EXCLUDE USING gist (
        tenant_id WITH =,
        worker_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    )
    WHERE (status IN ('SUBMITTED', 'APPROVED'));

COMMENT ON CONSTRAINT ex_abs_leave_request_active_overlap ON abs_leave_requests IS
    'Prevents concurrent submitted or approved absence intervals for one worker.';
