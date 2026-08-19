CREATE TABLE mail_thread_folders (
    tenant_id BIGINT NOT NULL,
    thread_id UUID NOT NULL REFERENCES mail_threads(thread_id) ON DELETE CASCADE,
    folder_id UUID NOT NULL REFERENCES mail_folders(folder_id) ON DELETE CASCADE,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (thread_id, folder_id)
);

CREATE INDEX idx_mail_thread_folder_lookup
    ON mail_thread_folders(tenant_id, folder_id, thread_id);

CREATE UNIQUE INDEX uk_mail_thread_primary_folder
    ON mail_thread_folders(thread_id)
    WHERE is_primary = TRUE;

INSERT INTO mail_thread_folders (tenant_id, thread_id, folder_id, is_primary)
SELECT tenant_id, thread_id, folder_id, TRUE
  FROM mail_threads;

CREATE OR REPLACE FUNCTION synchronize_mail_thread_folder_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    previous_folder_type VARCHAR(24);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        UPDATE mail_thread_folders
           SET is_primary = FALSE
         WHERE thread_id = NEW.thread_id AND is_primary = TRUE;
    END IF;

    INSERT INTO mail_thread_folders (
        tenant_id, thread_id, folder_id, is_primary)
    VALUES (NEW.tenant_id, NEW.thread_id, NEW.folder_id, TRUE)
    ON CONFLICT (thread_id, folder_id) DO UPDATE
        SET is_primary = TRUE;

    IF TG_OP = 'UPDATE' AND OLD.folder_id <> NEW.folder_id THEN
        SELECT folder_type
          INTO previous_folder_type
          FROM mail_folders
         WHERE folder_id = OLD.folder_id;

        IF previous_folder_type = 'DRAFTS' THEN
            DELETE FROM mail_thread_folders
             WHERE thread_id = NEW.thread_id AND folder_id = OLD.folder_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mail_thread_folder_membership
AFTER INSERT OR UPDATE OF folder_id ON mail_threads
FOR EACH ROW
EXECUTE FUNCTION synchronize_mail_thread_folder_membership();

COMMENT ON TABLE mail_thread_folders IS
    'Provider-neutral folder memberships retained when a conversation belongs to inbox and sent views.';
