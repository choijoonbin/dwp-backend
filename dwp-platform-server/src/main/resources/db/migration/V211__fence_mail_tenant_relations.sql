-- Fail closed before strengthening tenant/account relationships. A mismatched
-- legacy row must be investigated rather than silently attached to a mailbox.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM mail_folders folder
          JOIN mail_accounts account ON account.account_id = folder.account_id
         WHERE account.tenant_id <> folder.tenant_id
    ) THEN
        RAISE EXCEPTION 'mail_folders contains a cross-tenant account relationship';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM mail_folders folder
          JOIN mail_folders parent ON parent.folder_id = folder.parent_folder_id
         WHERE parent.tenant_id <> folder.tenant_id
            OR parent.account_id <> folder.account_id
    ) THEN
        RAISE EXCEPTION 'mail_folders contains a cross-account parent relationship';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM mail_shared_inboxes inbox
          JOIN mail_accounts account ON account.account_id = inbox.account_id
         WHERE account.tenant_id <> inbox.tenant_id
            OR account.account_kind <> 'SHARED'
    ) THEN
        RAISE EXCEPTION 'mail_shared_inboxes contains an invalid tenant or account relationship';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM mail_shared_inbox_members membership
          JOIN mail_shared_inboxes inbox
            ON inbox.shared_inbox_id = membership.shared_inbox_id
         WHERE inbox.tenant_id <> membership.tenant_id
    ) THEN
        RAISE EXCEPTION 'mail_shared_inbox_members contains a cross-tenant inbox relationship';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM mail_threads thread
          JOIN mail_accounts account ON account.account_id = thread.account_id
          JOIN mail_folders folder ON folder.folder_id = thread.folder_id
          LEFT JOIN mail_folders previous_folder
            ON previous_folder.folder_id = thread.previous_folder_id
          LEFT JOIN mail_shared_inboxes inbox
            ON inbox.shared_inbox_id = thread.shared_inbox_id
         WHERE account.tenant_id <> thread.tenant_id
            OR folder.tenant_id <> thread.tenant_id
            OR folder.account_id <> thread.account_id
            OR (previous_folder.folder_id IS NOT NULL AND (
                previous_folder.tenant_id <> thread.tenant_id
                OR previous_folder.account_id <> thread.account_id))
            OR (inbox.shared_inbox_id IS NOT NULL AND (
                inbox.tenant_id <> thread.tenant_id
                OR inbox.account_id <> thread.account_id))
            OR (thread.shared_inbox_id IS NULL AND account.account_kind = 'SHARED')
            OR (thread.shared_inbox_id IS NOT NULL AND account.account_kind <> 'SHARED')
    ) THEN
        RAISE EXCEPTION 'mail_threads contains an invalid tenant, account, folder or shared inbox relationship';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM mail_thread_folders membership
          JOIN mail_threads thread ON thread.thread_id = membership.thread_id
          JOIN mail_folders folder ON folder.folder_id = membership.folder_id
         WHERE membership.tenant_id <> thread.tenant_id
            OR membership.tenant_id <> folder.tenant_id
            OR thread.account_id <> folder.account_id
    ) THEN
        RAISE EXCEPTION 'mail_thread_folders contains a cross-tenant or cross-account relationship';
    END IF;
END;
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_mail_folder_tenant_account_identity
    ON mail_folders (tenant_id, account_id, folder_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mail_shared_inbox_tenant_identity
    ON mail_shared_inboxes (tenant_id, shared_inbox_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mail_shared_inbox_tenant_account_identity
    ON mail_shared_inboxes (tenant_id, account_id, shared_inbox_id);

ALTER TABLE mail_shared_inbox_members
    ADD COLUMN account_id UUID;

UPDATE mail_shared_inbox_members membership
   SET account_id = inbox.account_id
  FROM mail_shared_inboxes inbox
 WHERE inbox.tenant_id = membership.tenant_id
   AND inbox.shared_inbox_id = membership.shared_inbox_id;

ALTER TABLE mail_shared_inbox_members
    ALTER COLUMN account_id SET NOT NULL;

ALTER TABLE mail_thread_folders
    ADD COLUMN account_id UUID;

UPDATE mail_thread_folders membership
   SET account_id = thread.account_id
  FROM mail_threads thread
 WHERE thread.tenant_id = membership.tenant_id
   AND thread.thread_id = membership.thread_id;

ALTER TABLE mail_thread_folders
    ALTER COLUMN account_id SET NOT NULL;

ALTER TABLE mail_folders
    ADD CONSTRAINT fk_mail_folder_tenant_account
        FOREIGN KEY (tenant_id, account_id)
        REFERENCES mail_accounts (tenant_id, account_id)
        ON DELETE CASCADE NOT VALID,
    ADD CONSTRAINT fk_mail_folder_parent_tenant_account
        FOREIGN KEY (tenant_id, account_id, parent_folder_id)
        REFERENCES mail_folders (tenant_id, account_id, folder_id)
        ON DELETE RESTRICT NOT VALID;

ALTER TABLE mail_shared_inboxes
    ADD CONSTRAINT fk_mail_shared_inbox_tenant_account
        FOREIGN KEY (tenant_id, account_id)
        REFERENCES mail_accounts (tenant_id, account_id)
        ON DELETE RESTRICT NOT VALID;

ALTER TABLE mail_shared_inbox_members
    ADD CONSTRAINT fk_mail_shared_member_tenant_account_inbox
        FOREIGN KEY (tenant_id, account_id, shared_inbox_id)
        REFERENCES mail_shared_inboxes (tenant_id, account_id, shared_inbox_id)
        ON DELETE CASCADE NOT VALID;

ALTER TABLE mail_threads
    ADD CONSTRAINT fk_mail_thread_tenant_account
        FOREIGN KEY (tenant_id, account_id)
        REFERENCES mail_accounts (tenant_id, account_id)
        ON DELETE CASCADE NOT VALID,
    ADD CONSTRAINT fk_mail_thread_tenant_account_folder
        FOREIGN KEY (tenant_id, account_id, folder_id)
        REFERENCES mail_folders (tenant_id, account_id, folder_id)
        ON DELETE RESTRICT NOT VALID,
    ADD CONSTRAINT fk_mail_thread_previous_tenant_account_folder
        FOREIGN KEY (tenant_id, account_id, previous_folder_id)
        REFERENCES mail_folders (tenant_id, account_id, folder_id)
        ON DELETE NO ACTION NOT VALID,
    ADD CONSTRAINT fk_mail_thread_tenant_account_shared_inbox
        FOREIGN KEY (tenant_id, account_id, shared_inbox_id)
        REFERENCES mail_shared_inboxes (tenant_id, account_id, shared_inbox_id)
        ON DELETE RESTRICT NOT VALID;

ALTER TABLE mail_thread_folders
    ADD CONSTRAINT fk_mail_thread_folder_tenant_account_thread
        FOREIGN KEY (tenant_id, account_id, thread_id)
        REFERENCES mail_threads (tenant_id, account_id, thread_id)
        ON DELETE CASCADE NOT VALID,
    ADD CONSTRAINT fk_mail_thread_folder_tenant_account_folder
        FOREIGN KEY (tenant_id, account_id, folder_id)
        REFERENCES mail_folders (tenant_id, account_id, folder_id)
        ON DELETE CASCADE NOT VALID;

ALTER TABLE mail_folders
    VALIDATE CONSTRAINT fk_mail_folder_tenant_account;
ALTER TABLE mail_folders
    VALIDATE CONSTRAINT fk_mail_folder_parent_tenant_account;
ALTER TABLE mail_shared_inboxes
    VALIDATE CONSTRAINT fk_mail_shared_inbox_tenant_account;
ALTER TABLE mail_shared_inbox_members
    VALIDATE CONSTRAINT fk_mail_shared_member_tenant_account_inbox;
ALTER TABLE mail_threads
    VALIDATE CONSTRAINT fk_mail_thread_tenant_account;
ALTER TABLE mail_threads
    VALIDATE CONSTRAINT fk_mail_thread_tenant_account_folder;
ALTER TABLE mail_threads
    VALIDATE CONSTRAINT fk_mail_thread_previous_tenant_account_folder;
ALTER TABLE mail_threads
    VALIDATE CONSTRAINT fk_mail_thread_tenant_account_shared_inbox;
ALTER TABLE mail_thread_folders
    VALIDATE CONSTRAINT fk_mail_thread_folder_tenant_account_thread;
ALTER TABLE mail_thread_folders
    VALIDATE CONSTRAINT fk_mail_thread_folder_tenant_account_folder;

CREATE OR REPLACE FUNCTION synchronize_mail_thread_folder_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    new_folder_type VARCHAR(24);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        UPDATE mail_thread_folders
           SET is_primary = FALSE
         WHERE tenant_id = NEW.tenant_id
           AND account_id = NEW.account_id
           AND thread_id = NEW.thread_id
           AND is_primary = TRUE;
    END IF;

    INSERT INTO mail_thread_folders (
        tenant_id, account_id, thread_id, folder_id, is_primary)
    VALUES (NEW.tenant_id, NEW.account_id, NEW.thread_id, NEW.folder_id, TRUE)
    ON CONFLICT (thread_id, folder_id) DO UPDATE
        SET tenant_id = EXCLUDED.tenant_id,
            account_id = EXCLUDED.account_id,
            is_primary = TRUE;

    SELECT folder.folder_type
      INTO new_folder_type
      FROM mail_folders folder
     WHERE folder.tenant_id = NEW.tenant_id
       AND folder.account_id = NEW.account_id
       AND folder.folder_id = NEW.folder_id;

    IF new_folder_type IN ('DRAFTS', 'ARCHIVE', 'SPAM', 'TRASH', 'CUSTOM') THEN
        DELETE FROM mail_thread_folders
         WHERE tenant_id = NEW.tenant_id
           AND account_id = NEW.account_id
           AND thread_id = NEW.thread_id
           AND folder_id <> NEW.folder_id;
    ELSIF new_folder_type IN ('INBOX', 'SENT') THEN
        DELETE FROM mail_thread_folders membership
         USING mail_folders folder
         WHERE membership.tenant_id = NEW.tenant_id
           AND membership.account_id = NEW.account_id
           AND membership.thread_id = NEW.thread_id
           AND membership.folder_id = folder.folder_id
           AND folder.tenant_id = membership.tenant_id
           AND folder.account_id = membership.account_id
           AND membership.folder_id <> NEW.folder_id
           AND folder.folder_type IN ('DRAFTS', 'ARCHIVE', 'SPAM', 'TRASH', 'CUSTOM');
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER trg_mail_thread_folder_membership ON mail_threads;

CREATE TRIGGER trg_mail_thread_folder_membership
AFTER INSERT OR UPDATE OF tenant_id, account_id, folder_id ON mail_threads
FOR EACH ROW
EXECUTE FUNCTION synchronize_mail_thread_folder_membership();

COMMENT ON COLUMN mail_shared_inbox_members.account_id IS
    'Tenant-fenced shared account identity used by composite relational constraints.';
COMMENT ON COLUMN mail_thread_folders.account_id IS
    'Tenant-fenced thread account identity used to prevent cross-mailbox folder membership.';

CREATE UNIQUE INDEX uk_mail_thread_tenant_identity
    ON mail_threads (tenant_id, thread_id);

CREATE TABLE mail_draft_command_receipts (
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    thread_id UUID,
    applied_version BIGINT,
    command_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, actor_user_id, command_type, idempotency_key),
    CONSTRAINT fk_mail_draft_receipt_tenant_thread
        FOREIGN KEY (tenant_id, thread_id)
        REFERENCES mail_threads (tenant_id, thread_id)
        ON DELETE RESTRICT
);

COMMENT ON TABLE mail_draft_command_receipts IS
    'Payload-bound durable receipts for replay-safe personal draft create and save commands.';
