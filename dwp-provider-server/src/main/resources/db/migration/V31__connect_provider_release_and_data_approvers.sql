CREATE TEMP TABLE tmp_provider_approval_operators (
    auth_user_id BIGINT PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_provider_approval_operators VALUES
    (900016, 'Provider Release Approver', 'PROVIDER_RELEASE_APPROVER'),
    (900017, 'Provider Data Policy Approver', 'PROVIDER_DATA_APPROVER');

INSERT INTO prv_operators (
    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
SELECT 1, auth_user_id, display_name, role_code, 'ACTIVE'
  FROM tmp_provider_approval_operators
ON CONFLICT (auth_tenant_id, auth_user_id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    role_code = EXCLUDED.role_code,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

DELETE FROM prv_operator_role_assignments assignment
USING prv_operators operator, tmp_provider_approval_operators seed
WHERE assignment.provider_operator_id = operator.provider_operator_id
  AND operator.auth_tenant_id = 1
  AND operator.auth_user_id = seed.auth_user_id
  AND assignment.role_code <> seed.role_code;

INSERT INTO prv_operator_role_assignments (
    provider_operator_id, role_code, lifecycle_state, valid_from, valid_to, created_by)
SELECT operator.provider_operator_id, seed.role_code,
       'ACTIVE', NULL, NULL, seed.auth_user_id
  FROM tmp_provider_approval_operators seed
  JOIN prv_operators operator
    ON operator.auth_tenant_id = 1
   AND operator.auth_user_id = seed.auth_user_id
ON CONFLICT (provider_operator_id, role_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    valid_from = NULL,
    valid_to = NULL;
