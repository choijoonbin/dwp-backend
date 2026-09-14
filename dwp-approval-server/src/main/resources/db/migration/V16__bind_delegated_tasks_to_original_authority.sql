ALTER TABLE apr_tasks
    ADD COLUMN delegated_authority_role_code VARCHAR(80);

-- V6 retained only the original user. Existing claimed delegations cannot be
-- distinguished perfectly, so candidate-backed rows are conservatively
-- treated as role authority and will be revalidated fail-closed.
UPDATE apr_tasks
   SET delegated_authority_role_code = candidate_role
 WHERE delegated_from_user_id IS NOT NULL
   AND candidate_role ~ '^[A-Z][A-Z0-9_]{1,79}$';

ALTER TABLE apr_tasks
    ADD CONSTRAINT ck_apr_task_delegated_authority_role CHECK (
        delegated_authority_role_code IS NULL
        OR (delegated_from_user_id IS NOT NULL
            AND delegated_authority_role_code ~ '^[A-Z][A-Z0-9_]{1,79}$'));

CREATE INDEX idx_apr_task_delegated_authority
    ON apr_tasks (tenant_id, delegated_from_user_id, delegated_authority_role_code)
    WHERE delegated_from_user_id IS NOT NULL;

COMMENT ON COLUMN apr_tasks.delegated_authority_role_code IS
    'Original candidate role used for a role-based delegation; NULL denotes a direct-assignee delegation.';
