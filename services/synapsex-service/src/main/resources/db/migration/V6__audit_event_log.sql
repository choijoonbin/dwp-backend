SET search_path TO dwp_aura, public;

CREATE TABLE IF NOT EXISTS dwp_aura.audit_event_log (
  audit_id           BIGSERIAL PRIMARY KEY,
  tenant_id          BIGINT NOT NULL,
  event_category     TEXT NOT NULL,
  event_type         TEXT NOT NULL,
  resource_type      TEXT,
  resource_id        TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_type         TEXT,
  actor_user_id      BIGINT,
  actor_agent_id     TEXT,
  actor_display_name TEXT,
  channel            TEXT,
  ip_address         TEXT,
  user_agent         TEXT,
  outcome            TEXT,
  severity           TEXT NOT NULL DEFAULT 'INFO',
  before_json        JSONB,
  after_json         JSONB,
  diff_json          JSONB,
  evidence_json      JSONB,
  tags               JSONB,
  gateway_request_id TEXT,
  trace_id           TEXT,
  span_id            TEXT
);

CREATE INDEX IF NOT EXISTS ix_audit_event_log_tenant_created
ON dwp_aura.audit_event_log(tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_audit_event_log_tenant_category_type
ON dwp_aura.audit_event_log(tenant_id, event_category, event_type, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_audit_event_log_resource
ON dwp_aura.audit_event_log(tenant_id, resource_type, resource_id);

CREATE INDEX IF NOT EXISTS ix_audit_event_log_actor
ON dwp_aura.audit_event_log(tenant_id, actor_type, actor_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_audit_event_log_outcome
ON dwp_aura.audit_event_log(tenant_id, outcome, created_at DESC);

COMMENT ON TABLE dwp_aura.audit_event_log IS 'Synapse 도메인 감사 이벤트 SoT.';
