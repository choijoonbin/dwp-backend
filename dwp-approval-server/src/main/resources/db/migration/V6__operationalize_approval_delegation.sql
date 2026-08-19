ALTER TABLE apr_delegations
    ADD COLUMN IF NOT EXISTS delegate_person_public_id UUID,
    ADD COLUMN IF NOT EXISTS delegate_display_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS delegate_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS delegated_role_codes JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE apr_delegations
   SET delegate_display_name = 'User ' || delegate_user_id
 WHERE delegate_display_name IS NULL OR BTRIM(delegate_display_name) = '';

ALTER TABLE apr_delegations
    ALTER COLUMN delegate_display_name SET NOT NULL;

ALTER TABLE apr_delegations
    DROP CONSTRAINT IF EXISTS ck_apr_delegation_roles;
ALTER TABLE apr_delegations
    ADD CONSTRAINT ck_apr_delegation_roles CHECK (
        jsonb_typeof(delegated_role_codes) = 'array');

ALTER TABLE apr_tasks
    ADD COLUMN IF NOT EXISTS decision_actor_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS decision_actor_person_public_id UUID,
    ADD COLUMN IF NOT EXISTS delegated_from_user_id BIGINT;

UPDATE apr_tasks task
   SET decision_actor_user_id = evidence.actor_id::BIGINT
  FROM (
        SELECT DISTINCT ON (event.tenant_id, event.event_data ->> 'taskId')
               event.tenant_id,
               event.event_data ->> 'taskId' AS task_id,
               event.actor_id
          FROM apr_request_events event
         WHERE event.event_type IN ('TASK_APPROVED', 'TASK_REJECTED', 'INFORMATION_REQUESTED')
           AND event.actor_type = 'USER'
           AND event.actor_id ~ '^[0-9]+$'
           AND event.event_data ? 'taskId'
         ORDER BY event.tenant_id, event.event_data ->> 'taskId', event.occurred_at DESC
       ) evidence
 WHERE task.tenant_id = evidence.tenant_id
   AND task.task_id::TEXT = evidence.task_id
   AND task.decision_actor_user_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_apr_delegation_delegate_active
    ON apr_delegations (tenant_id, delegate_user_id, lifecycle_state, starts_at, ends_at);

CREATE INDEX IF NOT EXISTS idx_apr_task_decision_actor
    ON apr_tasks (tenant_id, decision_actor_user_id, completed_at DESC)
    WHERE decision_actor_user_id IS NOT NULL;
