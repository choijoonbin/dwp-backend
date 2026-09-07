-- Preserve every legacy event. Only records with a provable object binding are readable.
ALTER TABLE wrk_activity_events
    ADD COLUMN event_kind VARCHAR(20) NOT NULL DEFAULT 'CHANGE',
    ADD COLUMN source_event_id VARCHAR(240),
    ADD COLUMN object_id VARCHAR(240),
    ADD COLUMN execution_id VARCHAR(240),
    ADD COLUMN execution_version BIGINT,
    ADD COLUMN attempt INTEGER,
    ADD COLUMN work_status VARCHAR(24),
    ADD COLUMN correlation_id VARCHAR(128),
    ADD COLUMN audit_record_id UUID,
    ADD COLUMN data_provenance VARCHAR(20) NOT NULL DEFAULT 'QUARANTINED';

UPDATE wrk_activity_events
   SET data_provenance = 'SAMPLE'
 WHERE audit_reference IN ('AUD-WRK-901','AUD-WRK-902','AUD-WRK-903','AUD-WRK-904')
    OR audit_reference LIKE 'HOME-SEED-%'
    OR activity_event_id IN (
        'a1000000-0000-0000-0000-000000000001',
        'a1000000-0000-0000-0000-000000000002',
        'a1000000-0000-0000-0000-000000000003',
        'a1000000-0000-0000-0000-000000000004');

-- Runtime app writes used this exact object-addressed route. Do not infer from a label.
UPDATE wrk_activity_events event
   SET object_id = app.app_key, data_provenance = 'LEGACY', event_kind = 'USAGE',
       source_event_id = event.activity_event_id::text
  FROM adm_workspace_apps app
 WHERE event.tenant_id = app.tenant_id AND event.object_type = 'WORKSPACE_APP'
   AND event.source_system = 'DWP Apps'
   AND event.data_provenance = 'QUARANTINED'
   AND event.source_route = '/apps?app=' || app.app_key;

-- The legacy writer put work_key at the start of both summaries. Use both + source,
-- tenant and historical audience; an ambiguous display label is never sufficient.
UPDATE wrk_activity_events event
   SET object_id = work.work_item_id::text, data_provenance = 'LEGACY',
       source_event_id = event.activity_event_id::text
  FROM wrk_items work
 WHERE event.tenant_id = work.tenant_id AND event.object_type = 'WORK_ITEM'
   AND event.data_provenance = 'QUARANTINED'
   AND event.visible_to_user_id IS NOT NULL
   AND work.work_type = 'TASK' AND work.source_system IN ('WORKSPACE','DWP_WORKSPACE')
   AND event.source_system = work.source_system
   AND starts_with(event.summary_ko, work.work_key || ' 상태가 ')
   AND starts_with(event.summary_en, work.work_key || ' ')
   AND event.audit_reference ~ '^AUD-WRK-[0-9a-fA-F-]{36}$';

ALTER TABLE wrk_activity_events DROP CONSTRAINT ck_wrk_activity_state;
ALTER TABLE wrk_activity_events
    ADD CONSTRAINT ck_wrk_activity_state CHECK
        (event_state IN ('RUNNING','NEEDS_INPUT','COMPLETED','POLICY_BLOCKED','FAILED','CANCELLED')),
    ADD CONSTRAINT ck_wrk_activity_kind CHECK (event_kind IN ('CHANGE','EXECUTION','USAGE')),
    ADD CONSTRAINT ck_wrk_activity_provenance CHECK
        (data_provenance IN ('LIVE','LEGACY','SAMPLE','QUARANTINED')),
    ADD CONSTRAINT ck_wrk_activity_live_binding CHECK
        (data_provenance <> 'LIVE' OR
            (visible_to_user_id IS NOT NULL AND visible_to_user_id > 0
             AND object_id IS NOT NULL AND length(btrim(object_id)) > 0
             AND source_event_id IS NOT NULL AND length(btrim(source_event_id)) > 0
             AND length(btrim(source_system)) > 0 AND audit_record_id IS NOT NULL)),
    ADD CONSTRAINT ck_wrk_activity_execution CHECK
        (event_kind <> 'EXECUTION' OR
            (execution_id IS NOT NULL AND length(btrim(execution_id)) > 0
             AND execution_version IS NOT NULL AND execution_version >= 0
             AND attempt IS NOT NULL AND attempt >= 1)),
    ADD CONSTRAINT ck_wrk_activity_change_outcome CHECK
        (data_provenance <> 'LIVE' OR event_kind = 'EXECUTION' OR event_state = 'COMPLETED'),
    ADD CONSTRAINT ck_wrk_activity_work_status CHECK
        (work_status IS NULL OR work_status IN ('DUE_SOON','IN_PROGRESS','WAITING','COMPLETED'));

-- Composite evidence reference prevents cross-tenant bindings. Deferred for shared
-- JPA/JDBC transactions: JPA may flush the audit entity after the JDBC event write.
ALTER TABLE sys_platform_audit_events
    ADD CONSTRAINT uk_platform_audit_tenant_id UNIQUE (tenant_id, audit_event_id);
ALTER TABLE wrk_activity_events ADD CONSTRAINT fk_activity_audit_evidence
    FOREIGN KEY (tenant_id, audit_record_id)
    REFERENCES sys_platform_audit_events (tenant_id, audit_event_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX uk_activity_source_event
    ON wrk_activity_events (tenant_id, source_system, source_event_id)
    WHERE source_event_id IS NOT NULL;
CREATE UNIQUE INDEX uk_activity_execution_version
    ON wrk_activity_events (tenant_id, source_system, execution_id, attempt, execution_version)
    WHERE event_kind = 'EXECUTION';
CREATE INDEX idx_activity_verified_cursor
    ON wrk_activity_events (tenant_id, visible_to_user_id, occurred_at DESC, activity_event_id DESC)
    WHERE data_provenance IN ('LIVE','LEGACY');
CREATE INDEX idx_activity_object_history
    ON wrk_activity_events (tenant_id, object_type, object_id, occurred_at DESC, activity_event_id DESC);
CREATE INDEX idx_activity_execution_latest
    ON wrk_activity_events (tenant_id, source_system, execution_id, attempt DESC, execution_version DESC)
    WHERE event_kind = 'EXECUTION' AND data_provenance = 'LIVE';

-- This view is a current-state read model, never a count of historical RUNNING rows.
-- Choose latest before applying viewer ACL: revocation must not expose an older state.
-- Attempts sequentially supersede prior attempts of ONE logical execution. Parallel
-- executions must use distinct execution_id values; attempts are not parallel branches.
CREATE VIEW wrk_activity_execution_current AS
SELECT DISTINCT ON (tenant_id, source_system, execution_id) *
  FROM wrk_activity_events
 WHERE event_kind = 'EXECUTION' AND data_provenance = 'LIVE'
 ORDER BY tenant_id, source_system, execution_id, attempt DESC, execution_version DESC,
          occurred_at DESC, activity_event_id DESC;

CREATE FUNCTION guard_verified_activity_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.data_provenance = 'LIVE' THEN
        RAISE EXCEPTION 'Verified activity events are append-only';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_verified_activity_immutable
    BEFORE UPDATE OR DELETE ON wrk_activity_events
    FOR EACH ROW EXECUTE FUNCTION guard_verified_activity_immutable();

INSERT INTO sys_code_values (code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_STATE','FAILED','Failed','{"ko":"실패","en":"Failed"}',50,'{}'),
       ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_STATE','CANCELLED','Cancelled','{"ko":"취소","en":"Cancelled"}',60,'{}')
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_KIND', 'dwp-platform-server', 'Activity fact kind',
     'Separates change facts, execution lifecycle events and low-value usage facts.',
     'SYSTEM', 'CHECK', 'wrk_activity_events.event_kind', 'REFERENCE'),
    ('PLATFORM.WORKSPACE_ACTIVITY.DATA_PROVENANCE', 'dwp-platform-server', 'Activity data provenance',
     'Verified runtime, safely linked legacy, sample or quarantined activity data.',
     'SYSTEM', 'CHECK', 'wrk_activity_events.data_provenance', 'OBSERVABILITY')
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_KIND','CHANGE','Change','{"ko":"변경 이력","en":"Change"}',10,'{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_KIND','EXECUTION','Execution','{"ko":"실행 이력","en":"Execution"}',20,'{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_KIND','USAGE','Usage','{"ko":"사용 이력","en":"Usage"}',30,'{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.DATA_PROVENANCE','LIVE','Verified live','{"ko":"검증된 실제 기록","en":"Verified live"}',10,'{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.DATA_PROVENANCE','LEGACY','Linked legacy','{"ko":"연결된 기존 기록","en":"Linked legacy"}',20,'{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.DATA_PROVENANCE','SAMPLE','Sample','{"ko":"샘플","en":"Sample"}',30,'{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.DATA_PROVENANCE','QUARANTINED','Quarantined','{"ko":"미검증 격리","en":"Quarantined"}',40,'{}')
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_bindings (code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_KIND','dwp-platform-server','DATABASE_COLUMN','wrk_activity_events.event_kind','CHECK'),
    ('PLATFORM.WORKSPACE_ACTIVITY.DATA_PROVENANCE','dwp-platform-server','DATABASE_COLUMN','wrk_activity_events.data_provenance','CHECK'),
    ('PLATFORM.WORK_ITEM.LIFECYCLE_STATE','dwp-platform-server','DATABASE_COLUMN','wrk_activity_events.work_status','CHECK')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO NOTHING;
