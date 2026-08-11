-- Provider-side identity for the isolated local review account created by the
-- auth V27 migration. Provider authorization requires both the auth role and
-- an active operator assignment in this control-plane database.

INSERT INTO prv_operators (
    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
VALUES (
    1, 900001, 'Provider Review Administrator', 'PROVIDER_ADMIN', 'ACTIVE')
ON CONFLICT (auth_tenant_id, auth_user_id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    role_code = 'PROVIDER_ADMIN',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

DELETE FROM prv_operator_role_assignments assignment
USING prv_operators operator
WHERE assignment.provider_operator_id = operator.provider_operator_id
  AND operator.auth_tenant_id = 1
  AND operator.auth_user_id = 900001
  AND assignment.role_code <> 'PROVIDER_ADMIN';

INSERT INTO prv_operator_role_assignments (
    provider_operator_id, role_code, lifecycle_state, created_by)
SELECT provider_operator_id, 'PROVIDER_ADMIN', 'ACTIVE', auth_user_id
FROM prv_operators
WHERE auth_tenant_id = 1
  AND auth_user_id = 900001
ON CONFLICT (provider_operator_id, role_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    valid_from = NULL,
    valid_to = NULL;
