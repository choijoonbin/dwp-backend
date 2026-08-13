-- Split high-impact provider duties so tenant provisioning, commercial
-- entitlement changes, and approval cannot be performed by one daily account.

INSERT INTO prv_operator_roles (role_code, display_name, description)
VALUES
    ('PROVIDER_TENANT_PROVISIONER', 'Provider tenant provisioner',
     'Creates and activates customer tenant foundations.'),
    ('PROVIDER_ENTITLEMENT_ADMIN', 'Provider entitlement administrator',
     'Maintains commercial tenant entitlements without tenant data access.'),
    ('PROVIDER_CHANGE_APPROVER', 'Provider change approver',
     'Independently approves gated provider operations.')
ON CONFLICT (role_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE';

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_TENANT_PROVISIONER', 'ESTATE_READ'),
    ('PROVIDER_TENANT_PROVISIONER', 'TENANT_WRITE'),
    ('PROVIDER_TENANT_PROVISIONER', 'OPERATION_EXECUTE'),
    ('PROVIDER_ENTITLEMENT_ADMIN', 'ESTATE_READ'),
    ('PROVIDER_ENTITLEMENT_ADMIN', 'ENTITLEMENT_WRITE'),
    ('PROVIDER_ENTITLEMENT_ADMIN', 'COMMERCIAL_READ'),
    ('PROVIDER_CHANGE_APPROVER', 'ESTATE_READ'),
    ('PROVIDER_CHANGE_APPROVER', 'CHANGE_APPROVE')
ON CONFLICT (role_code, permission_code) DO NOTHING;

-- The legacy operator remains responsible for service operations. Tenant and
-- subscription mutation move to the dedicated roles above.
DELETE FROM prv_operator_role_permissions
 WHERE role_code = 'PROVIDER_OPERATOR'
   AND permission_code IN ('TENANT_WRITE', 'ENTITLEMENT_WRITE');

CREATE TEMP TABLE tmp_provider_duty_operators (
    auth_user_id BIGINT PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_provider_duty_operators VALUES
    (900010, 'Provider Tenant Provisioner', 'PROVIDER_TENANT_PROVISIONER'),
    (900011, 'Provider Entitlement Administrator', 'PROVIDER_ENTITLEMENT_ADMIN'),
    (900012, 'Provider Change Approver', 'PROVIDER_CHANGE_APPROVER'),
    (900013, 'Provider Service Operator', 'PROVIDER_OPERATOR'),
    (900014, 'Provider Support Operator', 'PROVIDER_SUPPORT'),
    (900015, 'Provider Auditor', 'PROVIDER_AUDITOR');

INSERT INTO prv_operators (
    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
SELECT 1, auth_user_id, display_name, role_code, 'ACTIVE'
  FROM tmp_provider_duty_operators
ON CONFLICT (auth_tenant_id, auth_user_id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    role_code = EXCLUDED.role_code,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

DELETE FROM prv_operator_role_assignments assignment
USING prv_operators operator, tmp_provider_duty_operators seed
WHERE assignment.provider_operator_id = operator.provider_operator_id
  AND operator.auth_tenant_id = 1
  AND operator.auth_user_id = seed.auth_user_id
  AND assignment.role_code <> seed.role_code;

INSERT INTO prv_operator_role_assignments (
    provider_operator_id, role_code, lifecycle_state, valid_from, valid_to, created_by)
SELECT operator.provider_operator_id, seed.role_code, 'ACTIVE', NULL, NULL, seed.auth_user_id
  FROM tmp_provider_duty_operators seed
  JOIN prv_operators operator
    ON operator.auth_tenant_id = 1
   AND operator.auth_user_id = seed.auth_user_id
ON CONFLICT (provider_operator_id, role_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    valid_from = NULL,
    valid_to = NULL;
