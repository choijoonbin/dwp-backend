INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('WIDGET_CATALOG_READ', 'Read widget catalog control plane', 'L1',
     'Read owned widget definitions, versions, readiness, and controls'),
    ('WIDGET_DEFINITION_WRITE', 'Write widget definitions', 'L2',
     'Create and update widget definitions and draft versions within an explicit owner scope'),
    ('WIDGET_DEFINITION_REVIEW', 'Review widget definitions', 'L3',
     'Review certification evidence for widget versions within an explicit owner scope'),
    ('WIDGET_DEFINITION_RELEASE', 'Release widget definitions', 'L3',
     'Publish and promote widget versions within an explicit owner scope'),
    ('WIDGET_DEFINITION_REVOKE', 'Revoke widget definitions', 'L3',
     'Block, quarantine, revoke, and disable owned widget resources')
ON CONFLICT (permission_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    risk_tier = EXCLUDED.risk_tier,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'WIDGET_CATALOG_READ'),
    ('PROVIDER_ADMIN', 'WIDGET_DEFINITION_WRITE'),
    ('PROVIDER_ADMIN', 'WIDGET_DEFINITION_REVIEW'),
    ('PROVIDER_ADMIN', 'WIDGET_DEFINITION_RELEASE'),
    ('PROVIDER_ADMIN', 'WIDGET_DEFINITION_REVOKE'),
    ('PROVIDER_OPERATOR', 'WIDGET_CATALOG_READ'),
    ('PROVIDER_OPERATOR', 'WIDGET_DEFINITION_WRITE'),
    ('PROVIDER_CHANGE_APPROVER', 'WIDGET_CATALOG_READ'),
    ('PROVIDER_CHANGE_APPROVER', 'WIDGET_DEFINITION_REVIEW'),
    ('PROVIDER_CHANGE_APPROVER', 'WIDGET_DEFINITION_RELEASE'),
    ('PROVIDER_CHANGE_APPROVER', 'WIDGET_DEFINITION_REVOKE'),
    ('PROVIDER_AUDITOR', 'WIDGET_CATALOG_READ')
ON CONFLICT (role_code, permission_code) DO NOTHING;

-- Owner authority is attached to an operator, rather than inferred from its broad role.
-- New operators and new product owners receive no scope until explicitly provisioned.
CREATE TABLE prv_operator_widget_owner_scopes (
    provider_operator_id BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    owner_product_key VARCHAR(120) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    PRIMARY KEY (provider_operator_id, owner_product_key),
    CONSTRAINT ck_prv_widget_owner_scope_key CHECK (
        owner_product_key ~ '^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$'),
    CONSTRAINT ck_prv_widget_owner_scope_state CHECK (
        lifecycle_state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_prv_widget_owner_scope_validity CHECK (
        valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from)
);

INSERT INTO prv_operator_widget_owner_scopes (
    provider_operator_id, owner_product_key, created_by)
SELECT DISTINCT assignment.provider_operator_id, owner.owner_product_key, 1
  FROM prv_operator_role_assignments assignment
 CROSS JOIN (VALUES
       ('core.workspace'),
       ('core.work'),
       ('core.calendar'),
       ('core.activity')) owner(owner_product_key)
 WHERE assignment.lifecycle_state = 'ACTIVE'
   AND assignment.role_code IN (
       'PROVIDER_ADMIN', 'PROVIDER_OPERATOR',
       'PROVIDER_CHANGE_APPROVER', 'PROVIDER_AUDITOR')
ON CONFLICT (provider_operator_id, owner_product_key) DO NOTHING;

CREATE INDEX idx_prv_widget_owner_scope_active
    ON prv_operator_widget_owner_scopes(provider_operator_id, owner_product_key)
    WHERE lifecycle_state = 'ACTIVE';
