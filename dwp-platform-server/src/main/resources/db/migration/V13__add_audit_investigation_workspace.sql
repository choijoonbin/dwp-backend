ALTER TABLE sys_audit_cases
    ADD COLUMN due_at TIMESTAMPTZ;

UPDATE sys_audit_cases
   SET due_at = opened_at + CASE severity
       WHEN 'CRITICAL' THEN INTERVAL '4 hours'
       WHEN 'HIGH' THEN INTERVAL '1 day'
       WHEN 'MEDIUM' THEN INTERVAL '3 days'
       ELSE INTERVAL '7 days'
   END
 WHERE due_at IS NULL;

ALTER TABLE sys_audit_cases
    ALTER COLUMN due_at SET NOT NULL;

CREATE INDEX idx_sys_audit_case_tenant_due
    ON sys_audit_cases(tenant_id, due_at)
    WHERE status NOT IN ('RESOLVED', 'CLOSED');

CREATE TABLE sys_audit_case_entities (
    case_id UUID NOT NULL REFERENCES sys_audit_cases(case_id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    entity_type VARCHAR(24) NOT NULL,
    entity_id VARCHAR(240) NOT NULL,
    display_name VARCHAR(320),
    relationship VARCHAR(80) NOT NULL,
    risk_score SMALLINT NOT NULL DEFAULT 0,
    first_seen_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    added_by VARCHAR(160) NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (case_id, entity_type, entity_id),
    CONSTRAINT ck_sys_audit_case_entity_type CHECK (entity_type IN (
        'USER', 'SERVICE', 'RESOURCE', 'APPLICATION', 'DATA', 'AI_AGENT', 'OTHER')),
    CONSTRAINT ck_sys_audit_case_entity_risk CHECK (risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_sys_audit_case_entity_attributes CHECK (jsonb_typeof(attributes) = 'object')
);

CREATE INDEX idx_sys_audit_case_entity_tenant_lookup
    ON sys_audit_case_entities(tenant_id, entity_type, entity_id);

CREATE TABLE sys_audit_case_activities (
    activity_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id UUID NOT NULL REFERENCES sys_audit_cases(case_id),
    tenant_id BIGINT NOT NULL,
    activity_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_audit_case_activity_type CHECK (activity_type IN (
        'CASE_CREATED', 'CASE_UPDATED', 'STATUS_CHANGED', 'ASSIGNMENT_CHANGED',
        'FINDING_LINKED', 'EVIDENCE_LINKED', 'NOTE_ADDED', 'TASK_CREATED',
        'TASK_UPDATED', 'RESOLUTION_RECORDED')),
    CONSTRAINT ck_sys_audit_case_activity_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX idx_sys_audit_case_activity_timeline
    ON sys_audit_case_activities(case_id, occurred_at DESC, activity_id);

CREATE OR REPLACE FUNCTION sys_reject_audit_case_activity_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'sys_audit_case_activities is append-only';
END;
$$;

CREATE TRIGGER trg_sys_audit_case_activities_immutable
BEFORE UPDATE OR DELETE ON sys_audit_case_activities
FOR EACH ROW EXECUTE FUNCTION sys_reject_audit_case_activity_mutation();

CREATE TABLE sys_audit_case_tasks (
    task_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id UUID NOT NULL REFERENCES sys_audit_cases(case_id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL,
    description VARCHAR(2000),
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    owner_actor_id VARCHAR(160),
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    updated_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_audit_case_task_status
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE', 'SKIPPED')),
    CONSTRAINT ck_sys_audit_case_task_priority
        CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_sys_audit_case_task_completion CHECK (
        (status = 'DONE' AND completed_at IS NOT NULL)
        OR (status <> 'DONE' AND completed_at IS NULL))
);

CREATE INDEX idx_sys_audit_case_task_queue
    ON sys_audit_case_tasks(tenant_id, status, due_at, priority)
    WHERE status IN ('OPEN', 'IN_PROGRESS');

INSERT INTO sys_audit_case_events (
    case_id, event_id, event_occurred_at, added_by, note, added_at)
SELECT f.case_id, f.event_id, f.event_occurred_at,
       COALESCE(f.assigned_to, c.created_by),
       'Linked from the originating finding', f.updated_at
  FROM sys_audit_findings f
  JOIN sys_audit_cases c ON c.case_id = f.case_id AND c.tenant_id = f.tenant_id
 WHERE f.case_id IS NOT NULL
   AND f.event_id IS NOT NULL
   AND f.event_occurred_at IS NOT NULL
ON CONFLICT (case_id, event_id) DO NOTHING;

INSERT INTO sys_audit_case_entities (
    case_id, tenant_id, entity_type, entity_id, display_name, relationship,
    risk_score, first_seen_at, last_seen_at, added_by, added_at)
SELECT f.case_id, f.tenant_id, 'USER', f.actor_id, f.actor_id, 'ACTOR',
       f.risk_score, f.first_seen_at, f.last_seen_at,
       COALESCE(f.assigned_to, c.created_by), f.updated_at
  FROM sys_audit_findings f
  JOIN sys_audit_cases c ON c.case_id = f.case_id AND c.tenant_id = f.tenant_id
 WHERE f.case_id IS NOT NULL AND f.actor_id IS NOT NULL
ON CONFLICT (case_id, entity_type, entity_id) DO UPDATE SET
    risk_score = GREATEST(sys_audit_case_entities.risk_score, EXCLUDED.risk_score),
    last_seen_at = GREATEST(sys_audit_case_entities.last_seen_at, EXCLUDED.last_seen_at);

INSERT INTO sys_audit_case_entities (
    case_id, tenant_id, entity_type, entity_id, display_name, relationship,
    risk_score, first_seen_at, last_seen_at, added_by, added_at)
SELECT f.case_id, f.tenant_id, 'RESOURCE', f.target_id, f.target_id, 'TARGET',
       f.risk_score, f.first_seen_at, f.last_seen_at,
       COALESCE(f.assigned_to, c.created_by), f.updated_at
  FROM sys_audit_findings f
  JOIN sys_audit_cases c ON c.case_id = f.case_id AND c.tenant_id = f.tenant_id
 WHERE f.case_id IS NOT NULL AND f.target_id IS NOT NULL
ON CONFLICT (case_id, entity_type, entity_id) DO UPDATE SET
    risk_score = GREATEST(sys_audit_case_entities.risk_score, EXCLUDED.risk_score),
    last_seen_at = GREATEST(sys_audit_case_entities.last_seen_at, EXCLUDED.last_seen_at);

INSERT INTO sys_audit_case_entities (
    case_id, tenant_id, entity_type, entity_id, display_name, relationship,
    risk_score, first_seen_at, last_seen_at, added_by, added_at)
SELECT f.case_id, f.tenant_id, 'SERVICE', f.source_service, f.source_service, 'SOURCE',
       f.risk_score, f.first_seen_at, f.last_seen_at,
       COALESCE(f.assigned_to, c.created_by), f.updated_at
  FROM sys_audit_findings f
  JOIN sys_audit_cases c ON c.case_id = f.case_id AND c.tenant_id = f.tenant_id
 WHERE f.case_id IS NOT NULL
ON CONFLICT (case_id, entity_type, entity_id) DO UPDATE SET
    risk_score = GREATEST(sys_audit_case_entities.risk_score, EXCLUDED.risk_score),
    last_seen_at = GREATEST(sys_audit_case_entities.last_seen_at, EXCLUDED.last_seen_at);

INSERT INTO sys_audit_case_activities (
    case_id, tenant_id, activity_type, actor_id, message, payload, occurred_at)
SELECT case_id, tenant_id, 'CASE_CREATED', created_by,
       'Investigation case created',
       jsonb_build_object('severity', severity, 'status', status), created_at
  FROM sys_audit_cases;

COMMENT ON TABLE sys_audit_case_activities IS
    'Append-only investigator activity timeline including notes, evidence links, tasks, and workflow decisions.';
COMMENT ON TABLE sys_audit_case_entities IS
    'Tenant-scoped entities and relationships that define the blast radius of an audit investigation.';
COMMENT ON TABLE sys_audit_case_tasks IS
    'Accountable investigation work items with ownership, due dates, and completion state.';
