ALTER TABLE apr_tasks
    ADD COLUMN decision_payload_revision INTEGER,
    ADD COLUMN decision_payload_sha256 CHAR(64),
    ADD COLUMN decision_invalidated_at TIMESTAMPTZ,
    ADD COLUMN decision_invalidation_reason VARCHAR(160);

-- V9 captured a baseline for every payload that existed at upgrade time. Local
-- repeatable fixtures (and any equivalent legacy importer) could subsequently
-- create a request without appending that immutable row. Recover only when the
-- mutable payload is provably no newer than every recorded decision.
INSERT INTO apr_request_payload_versions (
    payload_version_id, tenant_id, request_id, revision_number,
    payload, payload_sha256, change_type, changed_by, change_reason, created_at)
SELECT md5('approval-payload-baseline:v15:'
           || payload.tenant_id || ':' || payload.request_id)::uuid,
       payload.tenant_id, payload.request_id, payload.schema_version,
       payload.payload, payload.payload_sha256, 'BASELINE', request.updated_by,
       'Legacy payload baseline recovered during decision evidence upgrade',
       payload.updated_at
  FROM apr_request_payloads payload
  JOIN apr_requests request
    ON request.tenant_id = payload.tenant_id
   AND request.request_id = payload.request_id
 WHERE NOT EXISTS (
           SELECT 1
             FROM apr_request_payload_versions version
            WHERE version.tenant_id = payload.tenant_id
              AND version.request_id = payload.request_id)
   AND EXISTS (
           SELECT 1
             FROM apr_tasks task
            WHERE task.tenant_id = payload.tenant_id
              AND task.request_id = payload.request_id
              AND task.status IN ('APPROVED', 'REJECTED', 'INFO_REQUESTED'))
   AND payload.updated_at <= ALL (
           SELECT COALESCE(task.completed_at, task.updated_at)
             FROM apr_tasks task
            WHERE task.tenant_id = payload.tenant_id
              AND task.request_id = payload.request_id
              AND task.status IN ('APPROVED', 'REJECTED', 'INFO_REQUESTED'))
ON CONFLICT (tenant_id, request_id, revision_number) DO NOTHING;

WITH historical_payload AS (
    SELECT task.tenant_id,
           task.task_id,
           evidence.revision_number,
           evidence.payload_sha256
      FROM apr_tasks task
      JOIN LATERAL (
          SELECT version.revision_number, version.payload_sha256
            FROM apr_request_payload_versions version
           WHERE version.tenant_id = task.tenant_id
             AND version.request_id = task.request_id
             AND version.created_at <= COALESCE(task.completed_at, task.updated_at)
           ORDER BY version.created_at DESC, version.revision_number DESC
           LIMIT 1
      ) evidence ON TRUE
     WHERE task.status IN ('APPROVED', 'REJECTED', 'INFO_REQUESTED')
)
UPDATE apr_tasks task
   SET decision_payload_revision = historical_payload.revision_number,
       decision_payload_sha256 = historical_payload.payload_sha256
  FROM historical_payload
 WHERE task.tenant_id = historical_payload.tenant_id
   AND task.task_id = historical_payload.task_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM apr_tasks
         WHERE status IN ('APPROVED', 'REJECTED', 'INFO_REQUESTED')
           AND (decision_payload_revision IS NULL
                OR decision_payload_sha256 IS NULL)) THEN
        RAISE EXCEPTION
            'Cannot bind existing approval decisions to immutable payload history'
            USING ERRCODE = '23514';
    END IF;
END
$$;

ALTER TABLE apr_tasks
    DROP CONSTRAINT ck_apr_task_status,
    ADD CONSTRAINT ck_apr_task_status CHECK (status IN (
        'PENDING', 'CLAIMED', 'APPROVED', 'REJECTED', 'INFO_REQUESTED',
        'REASSIGNED', 'SKIPPED', 'CANCELLED', 'SUPERSEDED')),
    ADD CONSTRAINT ck_apr_task_decision_payload CHECK (
        (status IN ('APPROVED', 'REJECTED', 'INFO_REQUESTED', 'SUPERSEDED')
            AND decision_payload_revision IS NOT NULL
            AND decision_payload_revision > 0
            AND decision_payload_sha256 ~ '^[a-f0-9]{64}$')
        OR
        (status NOT IN ('APPROVED', 'REJECTED', 'INFO_REQUESTED', 'SUPERSEDED')
            AND decision_payload_revision IS NULL
            AND decision_payload_sha256 IS NULL)),
    ADD CONSTRAINT ck_apr_task_decision_invalidation CHECK (
        (status <> 'SUPERSEDED'
            AND decision_invalidated_at IS NULL
            AND decision_invalidation_reason IS NULL)
        OR
        (status = 'SUPERSEDED'
            AND decision_invalidated_at IS NOT NULL
            AND NULLIF(BTRIM(decision_invalidation_reason), '') IS NOT NULL));

CREATE INDEX idx_apr_task_decision_payload_revision
    ON apr_tasks (tenant_id, request_id, decision_payload_revision)
    WHERE decision_payload_revision IS NOT NULL;

COMMENT ON COLUMN apr_tasks.decision_payload_revision IS
    'Immutable request payload revision evaluated by the decision recorded on this task.';
COMMENT ON COLUMN apr_tasks.decision_payload_sha256 IS
    'Immutable SHA-256 of the request payload evaluated by the decision recorded on this task.';
COMMENT ON COLUMN apr_tasks.decision_invalidated_at IS
    'Time a material information-response amendment superseded this decision.';
