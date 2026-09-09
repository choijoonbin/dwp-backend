-- A personal Work mutation already commits an immutable command receipt, timeline fact and
-- platform audit record in one transaction. Project that committed command into Activity
-- without making the Work application depend on an Activity writer.
CREATE TABLE wrk_personal_work_activity_bindings (
    activity_event_id UUID PRIMARY KEY
        REFERENCES wrk_activity_events(activity_event_id) ON DELETE RESTRICT,
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    source_system VARCHAR(120) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    resource_id UUID NOT NULL,
    resource_version BIGINT NOT NULL CHECK (resource_version >= 0),
    idempotency_key UUID NOT NULL,
    result_state VARCHAR(32) NOT NULL,
    audit_record_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_personal_work_activity_source
        CHECK (source_system = 'PERSONAL_TASK' AND source_reference = resource_id::text),
    CONSTRAINT ck_personal_work_activity_result
        CHECK (result_state IN ('OPEN','IN_PROGRESS','WAITING','COMPLETED','ARCHIVED','DELETED')),
    CONSTRAINT uk_personal_work_activity_command
        UNIQUE (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT uk_personal_work_activity_version
        UNIQUE (tenant_id, actor_user_id, resource_id, resource_version),
    CONSTRAINT fk_personal_work_activity_receipt
        FOREIGN KEY (tenant_id, actor_user_id, idempotency_key)
        REFERENCES personal_work_command_receipts(tenant_id, owner_user_id, command_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_personal_work_activity_timeline
        FOREIGN KEY (tenant_id, actor_user_id, resource_id, resource_version)
        REFERENCES personal_work_timeline(tenant_id, owner_user_id, task_id, version)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_personal_work_activity_audit
        FOREIGN KEY (tenant_id, audit_record_id)
        REFERENCES sys_platform_audit_events(tenant_id, audit_event_id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_personal_work_activity_resource
    ON wrk_personal_work_activity_bindings
       (tenant_id, actor_user_id, resource_id, resource_version DESC);

CREATE FUNCTION reject_personal_work_activity_binding_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'personal Work Activity bindings are append-only';
END;
$$;
CREATE TRIGGER trg_personal_work_activity_binding_append_only
    BEFORE UPDATE OR DELETE ON wrk_personal_work_activity_bindings
    FOR EACH ROW EXECUTE FUNCTION reject_personal_work_activity_binding_mutation();

-- Once a receipt is projected, its command/result semantics become evidence. The legacy
-- request-fingerprint compatibility field is intentionally excluded: V228 clients may
-- canonicalize that hash after insertion, and it is not part of the Activity projection.
CREATE FUNCTION guard_bound_personal_work_receipt_evidence() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path FROM CURRENT AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM wrk_personal_work_activity_bindings binding
         WHERE binding.tenant_id = OLD.tenant_id
           AND binding.actor_user_id = OLD.owner_user_id
           AND binding.idempotency_key = OLD.command_id) THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Activity-bound personal Work receipts are immutable';
        END IF;
        IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
           OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
           OR NEW.command_id IS DISTINCT FROM OLD.command_id
           OR NEW.operation IS DISTINCT FROM OLD.operation
           OR NEW.target_key IS DISTINCT FROM OLD.target_key
           OR NEW.response_payload IS DISTINCT FROM OLD.response_payload
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Activity-bound personal Work receipt evidence is immutable';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_bound_personal_work_receipt_evidence
    BEFORE UPDATE OR DELETE ON personal_work_command_receipts
    FOR EACH ROW EXECUTE FUNCTION guard_bound_personal_work_receipt_evidence();

-- Platform audit rows serve other products too, so protect only rows referenced by this
-- binding. All audit fields are evidence once the personal Work command is projected.
CREATE FUNCTION guard_bound_personal_work_audit_evidence() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path FROM CURRENT AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM wrk_personal_work_activity_bindings binding
         WHERE binding.tenant_id = OLD.tenant_id
           AND binding.audit_record_id = OLD.audit_event_id) THEN
        IF TG_OP = 'DELETE' OR NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'Activity-bound personal Work audit evidence is immutable';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_bound_personal_work_audit_evidence
    BEFORE UPDATE OR DELETE ON sys_platform_audit_events
    FOR EACH ROW EXECUTE FUNCTION guard_bound_personal_work_audit_evidence();

-- This deferred assertion runs after JPA has flushed the audit row. It proves that the
-- receipt, timeline, audit record, Activity fact and binding all describe one command.
CREATE FUNCTION validate_personal_work_activity_binding() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path FROM CURRENT AS $$
DECLARE
    expected_source_event_id TEXT;
    expected_route TEXT;
    expected_result_state TEXT;
    expected_work_status TEXT;
BEGIN
    expected_source_event_id := 'personal-work-command:' || NEW.actor_user_id || ':' || NEW.idempotency_key;
    expected_route := format('/work/queue?work=PERSONAL_TASK%%3A%s%%3A', NEW.resource_id);

    SELECT CASE WHEN timeline.action = 'DELETED' THEN 'DELETED' ELSE timeline.status END,
           CASE WHEN timeline.status IN ('IN_PROGRESS','WAITING','COMPLETED')
                THEN timeline.status ELSE NULL END
      INTO expected_result_state, expected_work_status
      FROM personal_work_command_receipts receipt
      JOIN personal_work_timeline timeline
        ON timeline.tenant_id = receipt.tenant_id
       AND timeline.owner_user_id = receipt.owner_user_id
       AND timeline.task_id = NEW.resource_id
       AND timeline.version = NEW.resource_version
      JOIN sys_platform_audit_events audit
        ON audit.tenant_id = timeline.tenant_id
       AND audit.audit_event_id = timeline.audit_record_id
     WHERE receipt.tenant_id = NEW.tenant_id
       AND receipt.owner_user_id = NEW.actor_user_id
       AND receipt.command_id = NEW.idempotency_key
       AND receipt.response_payload->>'taskId' = NEW.resource_id::text
       AND (receipt.response_payload->>'version')::bigint = NEW.resource_version
       AND receipt.response_payload->>'status' = timeline.status
       AND ((receipt.operation = 'CREATE' AND receipt.target_key = 'tasks'
             AND timeline.action = 'CREATED')
         OR (receipt.operation = 'UPDATE' AND receipt.target_key = NEW.resource_id::text
             AND timeline.action = 'UPDATED')
         OR (receipt.operation = 'DELETE' AND receipt.target_key = NEW.resource_id::text
             AND timeline.action = 'DELETED'
             AND nullif(receipt.response_payload->>'deletedAt', '') IS NOT NULL)
         OR (receipt.operation = 'STATUS' AND receipt.target_key = NEW.resource_id::text
             AND timeline.action = timeline.status
             AND timeline.action IN ('OPEN','IN_PROGRESS','WAITING','COMPLETED','ARCHIVED')))
       AND audit.actor_type = 'USER'
       AND audit.actor_id = NEW.actor_user_id
       AND audit.action = 'personal-work.task.' || lower(timeline.action)
       AND audit.target_type = 'PERSONAL_WORK_TASK'
       AND audit.target_id = NEW.resource_id::text
       AND audit.outcome = 'SUCCESS'
       AND audit.audit_event_id = NEW.audit_record_id;

    IF NOT FOUND OR NEW.result_state <> expected_result_state THEN
        RAISE EXCEPTION 'personal Work Activity command evidence is not exactly bound';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM wrk_activity_events event
         WHERE event.activity_event_id = NEW.activity_event_id
           AND event.tenant_id = NEW.tenant_id
           AND event.visible_to_user_id = NEW.actor_user_id
           AND event.actor_kind = 'PERSON'
           AND event.event_state = 'COMPLETED'
           AND event.object_type = 'WORK_ITEM'
           AND event.source_system = NEW.source_system
           AND event.object_id = NEW.resource_id::text
           AND event.event_kind = 'CHANGE'
           AND event.source_event_id = expected_source_event_id
           AND event.source_route = expected_route
           AND event.work_status IS NOT DISTINCT FROM expected_work_status
           AND event.audit_record_id = NEW.audit_record_id
           AND event.audit_reference = NEW.audit_record_id::text
           AND event.data_provenance = 'LIVE') THEN
        RAISE EXCEPTION 'personal Work Activity event does not match its command binding';
    END IF;

    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_validate_personal_work_activity_binding
    AFTER INSERT ON wrk_personal_work_activity_bindings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_personal_work_activity_binding();

CREATE FUNCTION project_personal_work_command_activity(
    command_tenant_id BIGINT,
    command_actor_user_id BIGINT,
    command_idempotency_key UUID) RETURNS UUID
LANGUAGE plpgsql
SET search_path FROM CURRENT AS $$
DECLARE
    receipt personal_work_command_receipts%ROWTYPE;
    timeline personal_work_timeline%ROWTYPE;
    activity_id UUID;
    task_title TEXT;
    projected_result_state TEXT;
    activity_work_status TEXT;
    source_event_identity TEXT;
    canonical_route TEXT;
    observed_correlation_id TEXT;
BEGIN
    SELECT * INTO STRICT receipt
      FROM personal_work_command_receipts
     WHERE tenant_id = command_tenant_id
       AND owner_user_id = command_actor_user_id
       AND command_id = command_idempotency_key;

    IF receipt.operation NOT IN ('CREATE','UPDATE','STATUS','DELETE') THEN
        RETURN NULL;
    END IF;
    IF receipt.response_payload->>'taskId' IS NULL
       OR receipt.response_payload->>'version' IS NULL
       OR receipt.response_payload->>'status' IS NULL
       OR receipt.response_payload->>'title' IS NULL THEN
        RAISE EXCEPTION 'personal Work command receipt is missing projection evidence';
    END IF;

    SELECT * INTO STRICT timeline
      FROM personal_work_timeline
     WHERE tenant_id = receipt.tenant_id
       AND owner_user_id = receipt.owner_user_id
       AND task_id = (receipt.response_payload->>'taskId')::uuid
       AND version = (receipt.response_payload->>'version')::bigint;

    IF timeline.status <> receipt.response_payload->>'status'
       OR NOT ((receipt.operation = 'CREATE' AND receipt.target_key = 'tasks'
                AND timeline.action = 'CREATED')
            OR (receipt.operation = 'UPDATE' AND receipt.target_key = timeline.task_id::text
                AND timeline.action = 'UPDATED')
            OR (receipt.operation = 'DELETE' AND receipt.target_key = timeline.task_id::text
                AND timeline.action = 'DELETED'
                AND nullif(receipt.response_payload->>'deletedAt', '') IS NOT NULL)
            OR (receipt.operation = 'STATUS' AND receipt.target_key = timeline.task_id::text
                AND timeline.action = timeline.status
                AND timeline.action IN ('OPEN','IN_PROGRESS','WAITING','COMPLETED','ARCHIVED'))) THEN
        RAISE EXCEPTION 'personal Work receipt and timeline do not describe the same command';
    END IF;

    activity_id := md5('activity:personal-work:' || receipt.tenant_id || ':'
        || receipt.owner_user_id || ':' || receipt.command_id)::uuid;
    task_title := receipt.response_payload->>'title';
    projected_result_state := CASE WHEN timeline.action = 'DELETED' THEN 'DELETED' ELSE timeline.status END;
    activity_work_status := CASE WHEN timeline.status IN ('IN_PROGRESS','WAITING','COMPLETED')
        THEN timeline.status ELSE NULL END;
    source_event_identity := 'personal-work-command:' || receipt.owner_user_id || ':' || receipt.command_id;
    canonical_route := format('/work/queue?work=PERSONAL_TASK%%3A%s%%3A', timeline.task_id);
    SELECT audit.correlation_id INTO observed_correlation_id
      FROM sys_platform_audit_events audit
     WHERE audit.tenant_id = timeline.tenant_id
       AND audit.audit_event_id = timeline.audit_record_id;

    INSERT INTO wrk_activity_events (
        activity_event_id, tenant_id, visible_to_user_id, actor_kind, actor_name,
        event_state, title_ko, title_en, summary_ko, summary_en, object_type,
        object_label_ko, object_label_en, source_system, audit_reference,
        source_route, occurred_at, event_kind, source_event_id, object_id,
        work_status, correlation_id, audit_record_id, data_provenance)
    VALUES (
        activity_id, receipt.tenant_id, receipt.owner_user_id, 'PERSON',
        'User ' || receipt.owner_user_id, 'COMPLETED',
        CASE timeline.action
            WHEN 'CREATED' THEN '개인 업무 생성'
            WHEN 'UPDATED' THEN '개인 업무 수정'
            WHEN 'DELETED' THEN '개인 업무 삭제'
            ELSE '개인 업무 상태 변경' END,
        CASE timeline.action
            WHEN 'CREATED' THEN 'Personal task created'
            WHEN 'UPDATED' THEN 'Personal task updated'
            WHEN 'DELETED' THEN 'Personal task deleted'
            ELSE 'Personal task status changed' END,
        left(task_title || ' · 결과 ' || projected_result_state || ' · 버전 ' || timeline.version, 1000),
        left(task_title || ' · result ' || projected_result_state || ' · version ' || timeline.version, 1000),
        'WORK_ITEM', left(task_title, 240), left(task_title, 240), 'PERSONAL_TASK',
        timeline.audit_record_id::text, canonical_route, timeline.occurred_at, 'CHANGE',
        source_event_identity, timeline.task_id::text, activity_work_status,
        observed_correlation_id, timeline.audit_record_id, 'LIVE')
    ON CONFLICT DO NOTHING;

    INSERT INTO wrk_personal_work_activity_bindings (
        activity_event_id, tenant_id, actor_user_id, source_system, source_reference,
        resource_id, resource_version, idempotency_key, result_state, audit_record_id)
    VALUES (activity_id, receipt.tenant_id, receipt.owner_user_id, 'PERSONAL_TASK',
        timeline.task_id::text, timeline.task_id, timeline.version, receipt.command_id,
        projected_result_state, timeline.audit_record_id)
    ON CONFLICT DO NOTHING;

    IF NOT EXISTS (
        SELECT 1 FROM wrk_personal_work_activity_bindings binding
         WHERE binding.activity_event_id = activity_id
           AND binding.tenant_id = receipt.tenant_id
           AND binding.actor_user_id = receipt.owner_user_id
           AND binding.resource_id = timeline.task_id
           AND binding.resource_version = timeline.version
           AND binding.idempotency_key = receipt.command_id
           AND binding.result_state = projected_result_state
           AND binding.audit_record_id = timeline.audit_record_id) THEN
        RAISE EXCEPTION 'personal Work Activity projection identity collision';
    END IF;

    RETURN activity_id;
END;
$$;

CREATE FUNCTION project_inserted_personal_work_command_activity() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path FROM CURRENT AS $$
BEGIN
    PERFORM project_personal_work_command_activity(NEW.tenant_id, NEW.owner_user_id, NEW.command_id);
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_project_personal_work_command_activity
    AFTER INSERT ON personal_work_command_receipts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION project_inserted_personal_work_command_activity();

-- Existing receipts are real committed command evidence, so project them once as part of
-- the migration. The deterministic event identity and unique bindings make this idempotent.
SELECT project_personal_work_command_activity(tenant_id, owner_user_id, command_id)
  FROM personal_work_command_receipts
 WHERE operation IN ('CREATE','UPDATE','STATUS','DELETE')
 ORDER BY tenant_id, owner_user_id, created_at, command_id;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES (
    'PLATFORM.ACTIVITY.PERSONAL_WORK_RESULT_STATE', 'dwp-platform-server',
    'Personal Work Activity result state',
    'Immutable result state bound to a committed personal Work command receipt.',
    'SYSTEM', 'CHECK', 'wrk_personal_work_activity_bindings.result_state', 'STATE_MACHINE')
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.ACTIVITY.PERSONAL_WORK_RESULT_STATE','OPEN','Open','{"ko":"열림","en":"Open"}',10,'{}'),
    ('PLATFORM.ACTIVITY.PERSONAL_WORK_RESULT_STATE','IN_PROGRESS','In progress','{"ko":"진행 중","en":"In progress"}',20,'{}'),
    ('PLATFORM.ACTIVITY.PERSONAL_WORK_RESULT_STATE','WAITING','Waiting','{"ko":"대기","en":"Waiting"}',30,'{}'),
    ('PLATFORM.ACTIVITY.PERSONAL_WORK_RESULT_STATE','COMPLETED','Completed','{"ko":"완료","en":"Completed"}',40,'{}'),
    ('PLATFORM.ACTIVITY.PERSONAL_WORK_RESULT_STATE','ARCHIVED','Archived','{"ko":"보관됨","en":"Archived"}',50,'{}'),
    ('PLATFORM.ACTIVITY.PERSONAL_WORK_RESULT_STATE','DELETED','Deleted','{"ko":"삭제됨","en":"Deleted"}',60,'{}')
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES (
    'PLATFORM.ACTIVITY.PERSONAL_WORK_RESULT_STATE', 'dwp-platform-server',
    'DATABASE_COLUMN', 'wrk_personal_work_activity_bindings.result_state', 'CHECK')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO NOTHING;
