INSERT INTO adm_registry_entries (
    tenant_id,
    registry_type,
    entry_key,
    revision,
    name,
    description,
    owner_ref,
    risk_tier,
    artifact_version,
    lifecycle_state,
    created_by,
    updated_by
)
SELECT DISTINCT assistant.tenant_id,
       'AGENT',
       'REFERENCE_PLANNER',
       1,
       'Governed Reference Planner',
       'Deterministic preview plans for permission-scoped application handoffs',
       'agent:runtime',
       'MEDIUM',
       'reference-planner-v1',
       'ACTIVE',
       1,
       1
  FROM adm_registry_entries AS assistant
 WHERE assistant.registry_type = 'AGENT'
   AND assistant.entry_key = 'DWP_ASSISTANT'
   AND assistant.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, registry_type, entry_key, revision) DO UPDATE
SET name = EXCLUDED.name,
    description = EXCLUDED.description,
    owner_ref = EXCLUDED.owner_ref,
    risk_tier = EXCLUDED.risk_tier,
    artifact_version = EXCLUDED.artifact_version,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;
