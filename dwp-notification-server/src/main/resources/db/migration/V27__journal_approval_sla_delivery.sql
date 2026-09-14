-- Content-free delivery checkpoints. A Kafka record is not current Approval authority.
CREATE TABLE ntf_approval_sla_deliveries (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    event_id UUID NOT NULL,
    request_id UUID NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    original_envelope_sha256 VARCHAR(64) NOT NULL,
    canonical_envelope_sha256 VARCHAR(64) NOT NULL,
    recipient_snapshot_sha256 VARCHAR(64) NOT NULL,
    source_pins_sha256 VARCHAR(64) NOT NULL,
    recipient_count INTEGER NOT NULL CHECK (recipient_count BETWEEN 1 AND 1000),
    chunk_count INTEGER NOT NULL CHECK (chunk_count BETWEEN 1 AND 10),
    lease_epoch BIGINT NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, event_id),
    CHECK (chunk_count = (recipient_count + 99) / 100),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL))
);

CREATE TABLE ntf_approval_sla_delivery_chunks (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL CHECK (chunk_index BETWEEN 0 AND 9),
    recipient_count INTEGER NOT NULL CHECK (recipient_count BETWEEN 1 AND 100),
    outcome_sha256 VARCHAR(64) NOT NULL,
    authority_sha256 VARCHAR(64) NOT NULL,
    lease_epoch BIGINT NOT NULL CHECK (lease_epoch > 0),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, event_id, chunk_index),
    FOREIGN KEY (tenant_id, event_id)
        REFERENCES ntf_approval_sla_deliveries (tenant_id, event_id)
);

CREATE TABLE ntf_approval_sla_delivery_recipients (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    person_public_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_version BIGINT NOT NULL CHECK (task_version >= 0),
    child_event_id UUID NOT NULL,
    authority_eligible BOOLEAN NOT NULL,
    intent_id UUID,
    notification_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, event_id, user_id),
    UNIQUE (tenant_id, event_id, task_id),
    UNIQUE (tenant_id, child_event_id),
    FOREIGN KEY (tenant_id, event_id, chunk_index)
        REFERENCES ntf_approval_sla_delivery_chunks (tenant_id, event_id, chunk_index),
    CHECK (authority_eligible OR (intent_id IS NULL AND notification_id IS NULL))
);

CREATE INDEX ix_ntf_approval_sla_request
    ON ntf_approval_sla_deliveries (tenant_id, request_id, event_id);

ALTER TABLE ntf_approval_sla_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_approval_sla_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_approval_sla_delivery_worker ON ntf_approval_sla_deliveries
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());
ALTER TABLE ntf_approval_sla_delivery_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_approval_sla_delivery_chunks FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_approval_sla_chunk_worker ON ntf_approval_sla_delivery_chunks
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());
ALTER TABLE ntf_approval_sla_delivery_recipients ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_approval_sla_delivery_recipients FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_approval_sla_recipient_worker ON ntf_approval_sla_delivery_recipients
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

CREATE FUNCTION ntf_guard_approval_sla_delivery_identity()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.tenant_id, NEW.event_id, NEW.request_id, NEW.event_type,
           NEW.original_envelope_sha256, NEW.canonical_envelope_sha256,
           NEW.recipient_snapshot_sha256, NEW.source_pins_sha256,
           NEW.recipient_count, NEW.chunk_count, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.event_id, OLD.request_id, OLD.event_type,
           OLD.original_envelope_sha256, OLD.canonical_envelope_sha256,
           OLD.recipient_snapshot_sha256, OLD.source_pins_sha256,
           OLD.recipient_count, OLD.chunk_count, OLD.created_at)
       OR (OLD.finished_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Approval SLA delivery identity is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_ntf_approval_sla_delivery_identity
    BEFORE UPDATE ON ntf_approval_sla_deliveries
    FOR EACH ROW EXECUTE FUNCTION ntf_guard_approval_sla_delivery_identity();

CREATE FUNCTION ntf_guard_approval_sla_delivery_checkpoint()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Approval SLA delivery checkpoints are immutable';
END $$;
CREATE TRIGGER trg_ntf_approval_sla_chunk_immutable
    BEFORE UPDATE OR DELETE ON ntf_approval_sla_delivery_chunks
    FOR EACH ROW EXECUTE FUNCTION ntf_guard_approval_sla_delivery_checkpoint();
CREATE TRIGGER trg_ntf_approval_sla_recipient_immutable
    BEFORE UPDATE OR DELETE ON ntf_approval_sla_delivery_recipients
    FOR EACH ROW EXECUTE FUNCTION ntf_guard_approval_sla_delivery_checkpoint();

-- V2 grants future tables full worker DML. Narrow those inherited privileges explicitly.
REVOKE ALL ON ntf_approval_sla_deliveries, ntf_approval_sla_delivery_chunks,
    ntf_approval_sla_delivery_recipients FROM PUBLIC, dwp_notification_api;
REVOKE DELETE ON ntf_approval_sla_deliveries FROM dwp_notification_worker;
REVOKE UPDATE, DELETE ON ntf_approval_sla_delivery_chunks,
    ntf_approval_sla_delivery_recipients FROM dwp_notification_worker;
GRANT SELECT, INSERT, UPDATE ON ntf_approval_sla_deliveries TO dwp_notification_worker;
GRANT SELECT, INSERT ON ntf_approval_sla_delivery_chunks,
    ntf_approval_sla_delivery_recipients TO dwp_notification_worker;

COMMENT ON TABLE ntf_approval_sla_deliveries IS
    'Original-byte and canonical event digests; completion means every frozen seat was evaluated, not every seat notified.';
COMMENT ON TABLE ntf_approval_sla_delivery_chunks IS
    'Written in the same worker transaction as child intents, retention admission and current authority recheck.';
COMMENT ON TABLE ntf_approval_sla_delivery_recipients IS
    'Immutable per-seat delivery disposition, including current-authority exclusions. No source document content.';

SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

INSERT INTO ntf_notification_types
    (type_id,tenant_id,scope_type,scope_id,type_key,owner_app_key,owner_team,lifecycle_state)
VALUES
    ('10000000-0000-0000-0000-000000000101',NULL,'PROVIDER','dwp',
     'APPROVAL.SLA_WARNING','approvals','Workflow Platform','ACTIVE'),
    ('10000000-0000-0000-0000-000000000102',NULL,'PROVIDER','dwp',
     'APPROVAL.SLA_BREACHED','approvals','Workflow Platform','ACTIVE');

INSERT INTO ntf_notification_type_versions
    (type_version_id,tenant_id,type_id,version,source_event_type,min_schema_version,max_schema_version,
     priority,urgency,data_classification,contract_payload,lifecycle_state)
VALUES
    ('11000000-0000-0000-0000-000000000101',NULL,'10000000-0000-0000-0000-000000000101',1,
     'Approval.Quorum.SlaWarning',1,1,'HIGH','ACTIONABLE','INTERNAL',
     '{"audienceMode":"DIRECT","interruptionLevel":"ACTIVE","previewPolicy":"TITLE_ONLY","userConfigurable":true,"requiredVariables":["requestTitle","taskId"],"dedupeStrategy":"SOURCE_EVENT_RECIPIENT","retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE","runbookUrl":"/notifications/admin/operations?typeKey=APPROVAL.SLA_WARNING"}'::jsonb,'ACTIVE'),
    ('11000000-0000-0000-0000-000000000102',NULL,'10000000-0000-0000-0000-000000000102',1,
     'Approval.Quorum.SlaBreached',1,1,'URGENT','ACTIONABLE','INTERNAL',
     '{"audienceMode":"DIRECT","interruptionLevel":"ACTIVE","previewPolicy":"TITLE_ONLY","userConfigurable":true,"requiredVariables":["requestTitle","taskId"],"dedupeStrategy":"SOURCE_EVENT_RECIPIENT","retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE","runbookUrl":"/notifications/admin/operations?typeKey=APPROVAL.SLA_BREACHED"}'::jsonb,'ACTIVE');

INSERT INTO ntf_template_versions
    (template_version_id,tenant_id,type_version_id,channel,locale,version,title_template,
     preview_template,body_template,action_payload,state,checksum)
VALUES
    ('12000000-0000-0000-0000-000000000101',NULL,'11000000-0000-0000-0000-000000000101','IN_APP','ko-KR',1,
     '결재 검토 기한이 다가옵니다','{{requestTitle}}의 현재 결재 단계를 확인해 주세요.',
     '결재함에서 현재 담당 단계와 요청 내용을 확인해 주세요.',
     '{"label":"결재 검토","route":"/approvals/inbox?task={{taskId}}"}'::jsonb,'PUBLISHED','approval-sla-warning-ko-v1'),
    ('12000000-0000-0000-0000-000000000102',NULL,'11000000-0000-0000-0000-000000000101','IN_APP','en-US',1,
     'Approval review deadline is approaching','Review the current approval stage for {{requestTitle}}.',
     'Open your approval inbox to review the assigned stage and request.',
     '{"label":"Review approval","route":"/approvals/inbox?task={{taskId}}"}'::jsonb,'PUBLISHED','approval-sla-warning-en-v1'),
    ('12000000-0000-0000-0000-000000000103',NULL,'11000000-0000-0000-0000-000000000102','IN_APP','ko-KR',1,
     '결재 검토 기한이 지났습니다','{{requestTitle}}의 현재 결재 단계를 확인해 주세요.',
     '결재함에서 현재 담당 단계와 요청 내용을 확인해 주세요.',
     '{"label":"결재 검토","route":"/approvals/inbox?task={{taskId}}"}'::jsonb,'PUBLISHED','approval-sla-breached-ko-v1'),
    ('12000000-0000-0000-0000-000000000104',NULL,'11000000-0000-0000-0000-000000000102','IN_APP','en-US',1,
     'Approval review deadline has passed','Review the current approval stage for {{requestTitle}}.',
     'Open your approval inbox to review the assigned stage and request.',
     '{"label":"Review approval","route":"/approvals/inbox?task={{taskId}}"}'::jsonb,'PUBLISHED','approval-sla-breached-en-v1');
