CREATE TABLE apr_analytics_request_facts (
    tenant_id BIGINT NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    request_id UUID NOT NULL,
    workflow_version_id UUID NOT NULL,
    form_version_id UUID NOT NULL,
    request_status VARCHAR(24) NOT NULL,
    data_classification VARCHAR(20) NOT NULL,
    request_created_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cycle_seconds BIGINT,
    stage_count INTEGER NOT NULL DEFAULT 0 CHECK (stage_count >= 0),
    completed_stage_count INTEGER NOT NULL DEFAULT 0 CHECK (completed_stage_count >= 0),
    rework_count INTEGER NOT NULL DEFAULT 0 CHECK (rework_count >= 0),
    delegation_count INTEGER NOT NULL DEFAULT 0 CHECK (delegation_count >= 0),
    escalation_count INTEGER NOT NULL DEFAULT 0 CHECK (escalation_count >= 0),
    route_override_count INTEGER NOT NULL DEFAULT 0 CHECK (route_override_count >= 0),
    source_event_count INTEGER NOT NULL DEFAULT 0 CHECK (source_event_count >= 0),
    source_last_event_at TIMESTAMPTZ,
    projected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, management_resource_set_key, request_id),
    UNIQUE (tenant_id, request_id),
    FOREIGN KEY (tenant_id, request_id) REFERENCES apr_requests(tenant_id, request_id)
        ON DELETE CASCADE,
    CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CHECK (cycle_seconds IS NULL OR cycle_seconds >= 0),
    CHECK (completed_stage_count <= stage_count)
);

CREATE TABLE apr_analytics_stage_facts (
    tenant_id BIGINT NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    request_id UUID NOT NULL,
    step_id UUID NOT NULL,
    step_key VARCHAR(100) NOT NULL,
    sequence_number INTEGER NOT NULL CHECK (sequence_number > 0),
    step_status VARCHAR(24) NOT NULL,
    started_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    wait_seconds BIGINT,
    task_count INTEGER NOT NULL DEFAULT 0 CHECK (task_count >= 0),
    delegated_task_count INTEGER NOT NULL DEFAULT 0 CHECK (delegated_task_count >= 0),
    projected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, management_resource_set_key, request_id, step_id),
    FOREIGN KEY (tenant_id, management_resource_set_key, request_id)
        REFERENCES apr_analytics_request_facts(
            tenant_id, management_resource_set_key, request_id) ON DELETE CASCADE,
    CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CHECK (wait_seconds IS NULL OR wait_seconds >= 0),
    CHECK (delegated_task_count <= task_count)
);

CREATE INDEX idx_apr_analytics_request_window
    ON apr_analytics_request_facts (
        tenant_id, management_resource_set_key, submitted_at, request_id);
CREATE INDEX idx_apr_analytics_request_workflow
    ON apr_analytics_request_facts (
        tenant_id, management_resource_set_key, workflow_version_id, submitted_at);
CREATE INDEX idx_apr_analytics_stage_window
    ON apr_analytics_stage_facts (
        tenant_id, management_resource_set_key, started_at, step_key);

CREATE OR REPLACE FUNCTION refresh_approval_analytics_request(
    p_tenant BIGINT,
    p_request UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_scope VARCHAR(80);
BEGIN
    SELECT management_resource_set_key
      INTO v_scope
      FROM apr_requests
     WHERE tenant_id = p_tenant
       AND request_id = p_request
       FOR SHARE;

    IF v_scope IS NULL THEN
        DELETE FROM apr_analytics_request_facts
         WHERE tenant_id = p_tenant AND request_id = p_request;
        RETURN;
    END IF;

    DELETE FROM apr_analytics_request_facts
     WHERE tenant_id = p_tenant
       AND request_id = p_request
       AND management_resource_set_key <> v_scope;

    INSERT INTO apr_analytics_request_facts (
        tenant_id, management_resource_set_key, request_id,
        workflow_version_id, form_version_id, request_status,
        data_classification, request_created_at, submitted_at, due_at,
        completed_at, cycle_seconds,
        stage_count, completed_stage_count, rework_count, delegation_count,
        escalation_count, route_override_count, source_event_count,
        source_last_event_at, projected_at)
    SELECT request.tenant_id,
           request.management_resource_set_key,
           request.request_id,
           request.workflow_version_id,
           request.form_version_id,
           request.status,
           request.data_classification,
           request.created_at,
           request.submitted_at,
           request.due_at,
           request.completed_at,
           CASE
               WHEN request.submitted_at IS NOT NULL
                AND request.completed_at >= request.submitted_at
               THEN floor(extract(epoch FROM request.completed_at - request.submitted_at))::BIGINT
           END,
           (SELECT count(*)::INTEGER FROM apr_steps step
             WHERE step.tenant_id = request.tenant_id
               AND step.request_id = request.request_id),
           (SELECT count(*)::INTEGER FROM apr_steps step
             WHERE step.tenant_id = request.tenant_id
               AND step.request_id = request.request_id
               AND step.status IN ('APPROVED', 'REJECTED', 'SKIPPED', 'CANCELLED')),
           (SELECT count(*)::INTEGER FROM apr_request_events event
             WHERE event.tenant_id = request.tenant_id
               AND event.request_id = request.request_id
               AND (event.event_type ILIKE '%REWORK%'
                    OR event.event_type IN (
                        'INFORMATION_REQUESTED', 'REQUEST_INFO',
                        'REQUEST_INFORMATION_REQUESTED', 'TASK_INFORMATION_REQUESTED'))),
           (SELECT count(*)::INTEGER FROM apr_request_events event
             WHERE event.tenant_id = request.tenant_id
               AND event.request_id = request.request_id
               AND (event.event_type ILIKE '%DELEGAT%'
                    OR event.event_data ->> 'delegated' = 'true')),
           (SELECT count(*)::INTEGER FROM apr_request_events event
             WHERE event.tenant_id = request.tenant_id
               AND event.request_id = request.request_id
               AND event.event_type ILIKE '%ESCALAT%'),
           (SELECT count(*)::INTEGER FROM apr_request_events event
             WHERE event.tenant_id = request.tenant_id
               AND event.request_id = request.request_id
               AND (event.event_type ILIKE '%ROUTE%OVERRIDE%'
                    OR event.event_type = 'TASK_REASSIGNED_BY_OPERATOR')),
           (SELECT count(*)::INTEGER FROM apr_request_events event
             WHERE event.tenant_id = request.tenant_id
               AND event.request_id = request.request_id),
           (SELECT max(event.occurred_at) FROM apr_request_events event
             WHERE event.tenant_id = request.tenant_id
               AND event.request_id = request.request_id),
           clock_timestamp()
      FROM apr_requests request
     WHERE request.tenant_id = p_tenant
       AND request.request_id = p_request
    ON CONFLICT (tenant_id, management_resource_set_key, request_id)
    DO UPDATE SET
        workflow_version_id = EXCLUDED.workflow_version_id,
        form_version_id = EXCLUDED.form_version_id,
        request_status = EXCLUDED.request_status,
        data_classification = EXCLUDED.data_classification,
        request_created_at = EXCLUDED.request_created_at,
        submitted_at = EXCLUDED.submitted_at,
        due_at = EXCLUDED.due_at,
        completed_at = EXCLUDED.completed_at,
        cycle_seconds = EXCLUDED.cycle_seconds,
        stage_count = EXCLUDED.stage_count,
        completed_stage_count = EXCLUDED.completed_stage_count,
        rework_count = EXCLUDED.rework_count,
        delegation_count = EXCLUDED.delegation_count,
        escalation_count = EXCLUDED.escalation_count,
        route_override_count = EXCLUDED.route_override_count,
        source_event_count = EXCLUDED.source_event_count,
        source_last_event_at = EXCLUDED.source_last_event_at,
        projected_at = EXCLUDED.projected_at;

    DELETE FROM apr_analytics_stage_facts
     WHERE tenant_id = p_tenant
       AND request_id = p_request;

    INSERT INTO apr_analytics_stage_facts (
        tenant_id, management_resource_set_key, request_id, step_id,
        step_key, sequence_number, step_status, started_at, due_at,
        completed_at, wait_seconds, task_count, delegated_task_count, projected_at)
    SELECT step.tenant_id,
           request.management_resource_set_key,
           step.request_id,
           step.step_id,
           step.step_key,
           step.sequence_number,
           step.status,
           step.started_at,
           step.due_at,
           step.completed_at,
           CASE
               WHEN step.started_at IS NOT NULL
                AND step.completed_at >= step.started_at
               THEN floor(extract(epoch FROM step.completed_at - step.started_at))::BIGINT
           END,
           count(task.task_id)::INTEGER,
           count(task.task_id) FILTER (
               WHERE task.delegated_from_user_id IS NOT NULL)::INTEGER,
           clock_timestamp()
      FROM apr_steps step
      JOIN apr_requests request
        ON request.tenant_id = step.tenant_id
       AND request.request_id = step.request_id
      LEFT JOIN apr_tasks task
        ON task.tenant_id = step.tenant_id
       AND task.step_id = step.step_id
     WHERE step.tenant_id = p_tenant
       AND step.request_id = p_request
     GROUP BY step.tenant_id, request.management_resource_set_key,
              step.request_id, step.step_id, step.step_key, step.sequence_number,
              step.status, step.started_at, step.due_at, step.completed_at;
END
$$;

CREATE OR REPLACE FUNCTION refresh_approval_analytics_from_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM refresh_approval_analytics_request(
        COALESCE(NEW.tenant_id, OLD.tenant_id),
        COALESCE(NEW.request_id, OLD.request_id));
    RETURN COALESCE(NEW, OLD);
END
$$;

CREATE TRIGGER trg_apr_analytics_request
AFTER INSERT OR UPDATE ON apr_requests
FOR EACH ROW EXECUTE FUNCTION refresh_approval_analytics_from_row();
CREATE TRIGGER trg_apr_analytics_step
AFTER INSERT OR UPDATE OR DELETE ON apr_steps
FOR EACH ROW EXECUTE FUNCTION refresh_approval_analytics_from_row();
CREATE TRIGGER trg_apr_analytics_task
AFTER INSERT OR UPDATE OR DELETE ON apr_tasks
FOR EACH ROW EXECUTE FUNCTION refresh_approval_analytics_from_row();
CREATE TRIGGER trg_apr_analytics_event
AFTER INSERT ON apr_request_events
FOR EACH ROW EXECUTE FUNCTION refresh_approval_analytics_from_row();

DO $$
DECLARE
    request_row RECORD;
BEGIN
    FOR request_row IN SELECT tenant_id, request_id FROM apr_requests LOOP
        PERFORM refresh_approval_analytics_request(
            request_row.tenant_id, request_row.request_id);
    END LOOP;
END
$$;
