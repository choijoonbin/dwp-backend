-- Personal links never act as evidence of Calendar access, event existence or task completion.
CREATE TABLE personal_work_calendar_links (
    link_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    owner_user_id BIGINT NOT NULL CHECK (owner_user_id > 0),
    source_system VARCHAR(64) NOT NULL,
    source_reference VARCHAR(256) NOT NULL,
    obligation_key VARCHAR(160) NOT NULL DEFAULT '',
    event_id UUID NOT NULL,
    state VARCHAR(10) NOT NULL DEFAULT 'LINKED' CHECK (state IN ('LINKED', 'REMOVED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, owner_user_id, link_id)
);
CREATE UNIQUE INDEX uk_work_calendar_active_event
    ON personal_work_calendar_links (tenant_id, owner_user_id, event_id) WHERE state = 'LINKED';
CREATE INDEX idx_work_calendar_owner
    ON personal_work_calendar_links (tenant_id, owner_user_id, created_at DESC, link_id)
    WHERE state = 'LINKED';
