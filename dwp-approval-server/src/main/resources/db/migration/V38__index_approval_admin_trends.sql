CREATE INDEX idx_apr_request_management_submitted_trend
    ON apr_requests (
        tenant_id, management_resource_set_key, submitted_at, request_id)
    WHERE submitted_at IS NOT NULL;

CREATE INDEX idx_apr_request_management_completed_trend
    ON apr_requests (
        tenant_id, management_resource_set_key, completed_at, request_id)
    WHERE completed_at IS NOT NULL
      AND status IN ('APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED');

CREATE INDEX idx_apr_task_due_trend
    ON apr_tasks (tenant_id, due_at, request_id)
    INCLUDE (status, completed_at)
    WHERE due_at IS NOT NULL
      AND status IN ('PENDING', 'CLAIMED', 'APPROVED', 'REJECTED');

COMMENT ON INDEX idx_apr_request_management_submitted_trend IS
    'Tenant/resource-set bounded source for the 72-hour Approval admin submission trend.';
COMMENT ON INDEX idx_apr_request_management_completed_trend IS
    'Terminal request completion source for the 72-hour Approval admin trend.';
COMMENT ON INDEX idx_apr_task_due_trend IS
    'Covering source for due-task and SLA breach trend aggregation.';
