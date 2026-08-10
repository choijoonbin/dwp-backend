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
VALUES
    (1, 'APP', 'DWP_WORK', 1, 'Work', 'Priorities, approvals, and tasks', 'platform:workspace', 'LOW', '1.0.0', 'ACTIVE', 1, 1),
    (1, 'APP', 'DWP_ASK', 1, 'Ask DWP', 'Grounded answers and governed actions', 'platform:workspace', 'MEDIUM', '1.0.0', 'ACTIVE', 1, 1),
    (1, 'APP', 'DWP_ACTIVITY', 1, 'Activity', 'Human, system, and agent events', 'platform:workspace', 'LOW', '1.0.0', 'ACTIVE', 1, 1),
    (1, 'APP', 'DWP_APPS', 1, 'Apps', 'Available workplace applications', 'platform:workspace', 'LOW', '1.0.0', 'ACTIVE', 1, 1)
ON CONFLICT (tenant_id, registry_type, entry_key, revision) DO NOTHING;

UPDATE adm_navigation_items
SET registry_entry_key = CASE navigation_key
        WHEN 'work' THEN 'DWP_WORK'
        WHEN 'ask' THEN 'DWP_ASK'
        WHEN 'activity' THEN 'DWP_ACTIVITY'
        WHEN 'apps' THEN 'DWP_APPS'
    END,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE tenant_id = 1
  AND navigation_key IN ('work', 'ask', 'activity', 'apps');
