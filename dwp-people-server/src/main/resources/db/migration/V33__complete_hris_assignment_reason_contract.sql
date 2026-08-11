WITH tenants AS (
    SELECT tenant_id FROM sys_service_tenants
    UNION
    SELECT DISTINCT tenant_id FROM ppl_assignments
    UNION
    SELECT DISTINCT tenant_id FROM ppl_organizations
), reasons(reason_code, display_name, description, label_i18n, sort_order) AS (
    VALUES
        ('CONTRACT_START', 'Contract start', 'A contingent or fixed-term engagement started.', '{"ko":"계약 시작","en":"Contract start"}'::jsonb, 210),
        ('CONTRACT_END', 'Contract end', 'A contingent or fixed-term engagement ended.', '{"ko":"계약 종료","en":"Contract end"}'::jsonb, 220),
        ('SOURCE_OTHER', 'Other source event', 'A provider event was preserved after mapping to the governed fallback reason.', '{"ko":"기타 원천 이벤트","en":"Other source event"}'::jsonb, 900)
)
INSERT INTO ppl_assignment_change_reason_catalog (
    tenant_id,
    reason_code,
    display_name,
    description,
    label_i18n,
    sort_order,
    predefined,
    lifecycle_state,
    created_by,
    updated_by)
SELECT tenant.tenant_id,
       reason.reason_code,
       reason.display_name,
       reason.description,
       reason.label_i18n,
       reason.sort_order,
       TRUE,
       'ACTIVE',
       1,
       1
  FROM tenants tenant
 CROSS JOIN reasons reason
ON CONFLICT (tenant_id, reason_code) DO UPDATE
SET display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    predefined = TRUE,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
