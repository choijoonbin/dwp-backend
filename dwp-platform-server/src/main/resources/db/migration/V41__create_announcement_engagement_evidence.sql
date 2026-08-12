CREATE TABLE sys_announcement_engagements (
    announcement_engagement_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    announcement_id BIGINT NOT NULL
        REFERENCES adm_announcements(announcement_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    first_seen_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    seen_count BIGINT NOT NULL DEFAULT 0,
    first_action_at TIMESTAMPTZ,
    last_action_at TIMESTAMPTZ,
    action_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_announcement_engagement_user
        UNIQUE (tenant_id, announcement_id, user_id),
    CONSTRAINT ck_announcement_engagement_counts
        CHECK (seen_count >= 0 AND action_count >= 0)
);

CREATE INDEX idx_announcement_engagement_rollup
    ON sys_announcement_engagements(tenant_id, announcement_id, last_seen_at DESC);
