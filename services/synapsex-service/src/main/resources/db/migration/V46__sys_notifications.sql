-- Real-time notification bridge: store broadcast notifications for later retrieval (알림 센터)
CREATE TABLE IF NOT EXISTS dwp_aura.sys_notifications (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT         NOT NULL,
    user_id         BIGINT         NULL,                    -- null = broadcast to all in tenant
    title           VARCHAR(255)   NOT NULL,
    content         TEXT          NULL,
    type            VARCHAR(64)    NOT NULL,                 -- CASE_ACTION, RAG_STATUS, etc.
    channel         VARCHAR(128)   NOT NULL,                 -- source Redis channel
    occurred_at     TIMESTAMPTZ    NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    read_at         TIMESTAMPTZ    NULL,
    payload_json    JSONB          NULL,
    CONSTRAINT ch_sys_notifications_type CHECK (char_length(type) > 0),
    CONSTRAINT ch_sys_notifications_channel CHECK (char_length(channel) > 0)
);

CREATE INDEX idx_sys_notifications_tenant_created ON dwp_aura.sys_notifications (tenant_id, created_at DESC);
CREATE INDEX idx_sys_notifications_tenant_user_unread ON dwp_aura.sys_notifications (tenant_id, user_id) WHERE read_at IS NULL;

COMMENT ON TABLE dwp_aura.sys_notifications IS '알림 센터: Redis 실시간 이벤트 브로드캐스트 후 저장 (나중에 조회용)';
COMMENT ON COLUMN dwp_aura.sys_notifications.type IS '알림 유형: CASE_ACTION, RAG_STATUS 등';
COMMENT ON COLUMN dwp_aura.sys_notifications.channel IS '발생 소스 Redis 채널';
