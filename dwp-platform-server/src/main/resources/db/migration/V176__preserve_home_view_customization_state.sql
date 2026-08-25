-- Reset is a durable user state, not the absence of a preference row. Mirror
-- it on the active View so tenant-wide cutover readiness can compare metadata
-- without treating every reset user as a permanent mismatch.
ALTER TABLE usr_home_views
    ADD COLUMN IF NOT EXISTS is_customized BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE usr_home_views active
   SET is_customized = legacy.is_customized
  FROM usr_home_preferences legacy
 WHERE active.tenant_id = legacy.tenant_id
   AND active.user_id = legacy.user_id
   AND active.surface_key = legacy.surface_key
   AND active.is_default
   AND active.deleted_at IS NULL;
