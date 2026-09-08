-- Personal record metadata only: no transcript, media, names or access tickets.
CREATE TABLE vm_meeting_record_bookmarks (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    meeting_id UUID NOT NULL,
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, meeting_id),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE
);

CREATE INDEX ix_vm_record_bookmarks_favorites
    ON vm_meeting_record_bookmarks (tenant_id, user_id, meeting_id)
    WHERE favorite;

COMMENT ON TABLE vm_meeting_record_bookmarks IS
    'User-owned record bookmarks; every read and command rechecks current meeting access.';
