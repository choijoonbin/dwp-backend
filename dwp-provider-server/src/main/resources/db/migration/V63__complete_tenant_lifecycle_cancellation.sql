-- A cancelled lifecycle request has no owner handoff left to execute. Keep the
-- execution boundary explicit without implying that cancellation performed any
-- retirement or purge work.
ALTER TABLE prv_tenant_lifecycle_requests
    DROP CONSTRAINT ck_prv_tenant_lifecycle_request_execution;

UPDATE prv_tenant_lifecycle_requests
   SET execution_state = 'NOT_REQUIRED'
 WHERE lifecycle_state = 'CANCELLED';

ALTER TABLE prv_tenant_lifecycle_requests
    ADD CONSTRAINT ck_prv_tenant_lifecycle_request_execution CHECK (
        (lifecycle_state = 'CANCELLED' AND execution_state = 'NOT_REQUIRED')
        OR (lifecycle_state <> 'CANCELLED'
            AND execution_state = 'OWNER_HANDOFF_REQUIRED'));

COMMENT ON COLUMN prv_tenant_lifecycle_requests.execution_state IS
    'OWNER_HANDOFF_REQUIRED for open or decided requests; NOT_REQUIRED only after requester cancellation. Neither state asserts retirement or purge execution.';
