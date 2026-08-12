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
SELECT tenant.tenant_id,
       'AGENT',
       'DWP_ASSISTANT',
       1,
       'DWP Assistant',
       'Read-only grounded workplace answers with server-side policy and citation validation',
       'agent:runtime',
       'MEDIUM',
       'ask-runtime-v1',
       'ACTIVE',
       1,
       1
  FROM sys_service_tenants tenant
 WHERE tenant.lifecycle_state IN ('PROVISIONING', 'ACTIVE')
   AND NOT EXISTS (
       SELECT 1
         FROM adm_registry_entries existing
        WHERE existing.tenant_id = tenant.tenant_id
          AND existing.registry_type = 'AGENT'
          AND existing.entry_key = 'DWP_ASSISTANT'
          AND existing.lifecycle_state IN ('ACTIVE', 'DRAFT'));
