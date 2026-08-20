CREATE INDEX ix_msg_attachment_orphan_expiry
    ON msg_attachments (upload_expires_at, attachment_id)
    WHERE message_id IS NULL
      AND status IN ('QUARANTINED', 'CLEAN', 'REJECTED', 'EXPIRED');
