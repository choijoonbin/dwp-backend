-- Mail is a native baseline workforce product. Product ownership and
-- configuration accountability remain active, while access-package approval
-- duties from the retired combined Mail/Calendar boundary no longer apply.
UPDATE com_admin_role_assignments assignment
   SET lifecycle_state = 'REVOKED',
       revoked_by = COALESCE(assignment.approved_by, assignment.created_by),
       revoked_at = CURRENT_TIMESTAMP,
       revocation_reason = 'Mail is a native baseline product and does not use optional app access packages.',
       version = assignment.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_admin_resource_sets resource_set
 WHERE resource_set.tenant_id = assignment.tenant_id
   AND resource_set.resource_set_id = assignment.resource_set_id
   AND resource_set.resource_set_key = 'APP_MAIL'
   AND assignment.responsibility_code IN (
       'APP_ACCESS_MANAGER', 'APP_ACCESS_APPROVER', 'APP_ACCESS_REVIEWER')
   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE');
