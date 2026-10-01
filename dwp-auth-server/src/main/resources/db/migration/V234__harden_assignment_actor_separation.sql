-- Preserve the historical migration checksum and strengthen actor separation with
-- additive constraints. NOT VALID blocks new violations before the existing rows are scanned.

ALTER TABLE com_tenant_app_workforce_assignments
    ADD CONSTRAINT ck_tenant_app_assignment_reviewer_not_principal CHECK (
        lifecycle_state NOT IN ('APPROVED', 'ACTIVE')
        OR approved_by::text <> principal_ref) NOT VALID;

ALTER TABLE com_tenant_app_workforce_assignments
    ADD CONSTRAINT ck_tenant_app_assignment_activator_not_principal CHECK (
        lifecycle_state <> 'ACTIVE'
        OR activated_by::text <> principal_ref) NOT VALID;

ALTER TABLE com_tenant_app_workforce_assignments
    VALIDATE CONSTRAINT ck_tenant_app_assignment_reviewer_not_principal;

ALTER TABLE com_tenant_app_workforce_assignments
    VALIDATE CONSTRAINT ck_tenant_app_assignment_activator_not_principal;
