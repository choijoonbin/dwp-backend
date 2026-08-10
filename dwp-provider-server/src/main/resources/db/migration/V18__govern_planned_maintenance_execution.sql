INSERT INTO prv_operation_type_catalog (
    operation_type, display_name, default_risk_tier, execution_strategy,
    request_schema_version, request_schema)
VALUES (
    'MAINTENANCE_SCHEDULE',
    'Planned maintenance scheduling',
    'L3',
    'SINGLE_STEP',
    1,
    '{
      "type": "object",
      "required": ["trackingKey", "scopeType", "impactType", "startsAt", "endsAt"],
      "properties": {
        "trackingKey": {"type": "string"},
        "scopeType": {"type": "string"},
        "impactType": {"type": "string"},
        "startsAt": {"type": "string", "format": "date-time"},
        "endsAt": {"type": "string", "format": "date-time"}
      },
      "additionalProperties": true
    }'::jsonb)
ON CONFLICT (operation_type) DO NOTHING;

ALTER TABLE prv_maintenance_windows
    ALTER COLUMN operation_id SET NOT NULL,
    ADD CONSTRAINT uk_prv_maintenance_operation UNIQUE (operation_id);

