UPDATE vm_tenant_policies
   SET guests_allowed = FALSE,
       allow_join_before_host = FALSE,
       updated_at = CURRENT_TIMESTAMP
 WHERE guests_allowed = TRUE
    OR allow_join_before_host = TRUE;

ALTER TABLE vm_tenant_policies
    ALTER COLUMN guests_allowed SET DEFAULT FALSE;
