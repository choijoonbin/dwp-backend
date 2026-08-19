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
    updated_by)
SELECT DISTINCT app.tenant_id,
       'AGENT',
       'DWP_APPROVAL_EXPERT',
       1,
       'DWAI-ON Approval Expert',
       'Read-only approval intelligence for tasks, requests, forms, SLA, and evidence',
       'agent:approval',
       'MEDIUM',
       'approval-expert-v1',
       'ACTIVE',
       1,
       1
  FROM adm_workspace_apps app
  JOIN adm_registry_entries assistant
    ON assistant.tenant_id = app.tenant_id
   AND assistant.registry_type = 'AGENT'
   AND assistant.entry_key = 'DWP_ASSISTANT'
   AND assistant.lifecycle_state = 'ACTIVE'
 WHERE app.app_key = 'dwp-approvals'
   AND app.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, registry_type, entry_key, revision) DO UPDATE
SET name = EXCLUDED.name,
    description = EXCLUDED.description,
    owner_ref = EXCLUDED.owner_ref,
    risk_tier = EXCLUDED.risk_tier,
    artifact_version = EXCLUDED.artifact_version,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;
