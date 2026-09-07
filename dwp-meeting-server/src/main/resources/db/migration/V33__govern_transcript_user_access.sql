CREATE TABLE vm_meeting_transcript_access_windows (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, meeting_id, user_id, window_started_at),
    CONSTRAINT ck_vm_transcript_access_window_count
        CHECK (request_count BETWEEN 1 AND 30)
);

CREATE INDEX ix_vm_transcript_access_windows_expiry
    ON vm_meeting_transcript_access_windows (window_started_at);

COMMENT ON TABLE vm_meeting_transcript_access_windows IS
    'Content-free per-user abuse window for governed transcript reads.';
COMMENT ON COLUMN vm_meeting_transcript_access_windows.request_count IS
    'Count only; transcript text, search terms, locators, and access tokens are never stored.';
