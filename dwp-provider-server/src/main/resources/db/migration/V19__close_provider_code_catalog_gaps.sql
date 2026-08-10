ALTER TABLE prv_operation_type_catalog
    ADD COLUMN executable BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN prv_operation_type_catalog.executable IS
    'True only when the current provider runtime has an executable handler for this operation type.';

INSERT INTO prv_operation_type_catalog (
    operation_type, display_name, default_risk_tier, execution_strategy,
    lifecycle_state, request_schema_version, request_schema, executable)
VALUES
    ('DOMAIN_VERIFY', 'Verify tenant domain', 'L2', 'EXTERNAL_WORKFLOW',
     'RETIRED', 1, '{"type":"object"}'::jsonb, FALSE),
    ('SERVICE_RECONCILE', 'Reconcile tenant service', 'L2', 'SAGA',
     'RETIRED', 1, '{"type":"object"}'::jsonb, FALSE)
ON CONFLICT (operation_type) DO UPDATE
SET display_name = EXCLUDED.display_name,
    default_risk_tier = EXCLUDED.default_risk_tier,
    execution_strategy = EXCLUDED.execution_strategy,
    request_schema_version = EXCLUDED.request_schema_version,
    request_schema = EXCLUDED.request_schema,
    executable = EXCLUDED.executable,
    updated_at = CURRENT_TIMESTAMP;

ALTER TABLE prv_governance_controls
    ADD CONSTRAINT fk_prv_governance_controls_remediation_operation
        FOREIGN KEY (remediation_operation_type)
        REFERENCES prv_operation_type_catalog(operation_type);

ALTER TABLE prv_operators
    ADD CONSTRAINT fk_prv_operators_role
        FOREIGN KEY (role_code)
        REFERENCES prv_operator_roles(role_code);

ALTER TABLE prv_tenant_administrators
    ADD CONSTRAINT fk_prv_tenant_administrators_role_catalog
        FOREIGN KEY (role_code)
        REFERENCES prv_tenant_administrator_roles(role_code);

COMMENT ON CONSTRAINT fk_prv_governance_controls_remediation_operation
    ON prv_governance_controls IS
    'Every remediation code is registered even when the executable handler is not yet available.';
