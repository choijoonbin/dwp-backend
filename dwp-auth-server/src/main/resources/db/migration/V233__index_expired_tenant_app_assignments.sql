CREATE INDEX idx_tenant_app_assignment_expiry_release
    ON com_tenant_app_workforce_assignments (
        tenant_id, installation_id, principal_ref, valid_to)
    WHERE lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
      AND valid_to IS NOT NULL;

COMMENT ON INDEX idx_tenant_app_assignment_expiry_release IS
    'Supports serialized expiry release before a replacement workforce assignment is created.';
