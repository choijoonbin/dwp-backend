ALTER TABLE mail_group_recipient_snapshots
    ADD COLUMN account_id UUID;

UPDATE mail_group_recipient_snapshots snapshot
   SET account_id = thread.account_id
  FROM mail_threads thread
 WHERE thread.tenant_id = snapshot.tenant_id
   AND thread.thread_id = snapshot.thread_id;

ALTER TABLE mail_group_recipient_snapshots
    ALTER COLUMN account_id SET NOT NULL;

ALTER TABLE mail_group_recipient_snapshots
    ADD CONSTRAINT fk_mail_group_snapshot_account
        FOREIGN KEY (tenant_id, account_id)
        REFERENCES mail_accounts (tenant_id, account_id)
        ON DELETE RESTRICT;

ALTER TABLE mail_group_send_history
    ADD COLUMN account_id UUID;

UPDATE mail_group_send_history history
   SET account_id = thread.account_id
  FROM mail_threads thread
 WHERE thread.tenant_id = history.tenant_id
   AND thread.thread_id = history.thread_id;

ALTER TABLE mail_group_send_history
    ALTER COLUMN account_id SET NOT NULL;

ALTER TABLE mail_group_send_history
    ADD CONSTRAINT fk_mail_group_history_account
        FOREIGN KEY (tenant_id, account_id)
        REFERENCES mail_accounts (tenant_id, account_id)
        ON DELETE RESTRICT;
