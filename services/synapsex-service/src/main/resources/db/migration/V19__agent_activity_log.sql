-- Aura → Synapse: agent_activity_log (Agent Stream 전용)
-- Redis audit:events:ingest 수신 시 event_type → stage 매핑 후 저장

SET search_path TO dwp_aura, public;

CREATE TABLE IF NOT EXISTS dwp_aura.agent_activity_log (
    activity_id       BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL,
    stage             TEXT NOT NULL,
    event_type        TEXT,
    resource_type     TEXT,
    resource_id       TEXT,
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_agent_id    TEXT,
    actor_user_id     BIGINT,
    actor_display_name TEXT,
    metadata_json     JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_agent_activity_log_tenant_occurred
ON dwp_aura.agent_activity_log(tenant_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS ix_agent_activity_log_tenant_resource
ON dwp_aura.agent_activity_log(tenant_id, resource_type, resource_id);

COMMENT ON TABLE dwp_aura.agent_activity_log IS 'Aura 에이전트 활동 스트림. event_type→stage 매핑 적용.';
