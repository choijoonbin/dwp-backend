WITH tenants AS (
    SELECT tenant_id FROM sys_service_tenants
    UNION
    SELECT DISTINCT tenant_id FROM ppl_assignments
    UNION
    SELECT DISTINCT tenant_id FROM ppl_organizations
), reasons(reason_code, display_name, description, label_i18n, sort_order) AS (
    VALUES
        ('HIRE', 'Hire', 'Initial employment or contingent worker engagement.', '{"ko":"입사","en":"Hire"}'::jsonb, 100),
        ('REHIRE', 'Rehire', 'Employment resumed after a prior termination.', '{"ko":"재입사","en":"Rehire"}'::jsonb, 110),
        ('TERMINATION', 'Termination', 'Employment or engagement ended.', '{"ko":"퇴직","en":"Termination"}'::jsonb, 120),
        ('LEAVE_START', 'Leave start', 'Worker entered an approved leave period.', '{"ko":"휴직","en":"Leave start"}'::jsonb, 130),
        ('RETURN_FROM_LEAVE', 'Return from leave', 'Worker returned from an approved leave period.', '{"ko":"복직","en":"Return from leave"}'::jsonb, 140),
        ('DEMOTION', 'Demotion', 'Worker moved to a lower job or grade.', '{"ko":"강등","en":"Demotion"}'::jsonb, 150),
        ('MANAGER_CHANGE', 'Manager change', 'The assignment reporting manager changed.', '{"ko":"관리자 변경","en":"Manager change"}'::jsonb, 160),
        ('LOCATION_CHANGE', 'Location change', 'The assignment work location changed.', '{"ko":"근무지 변경","en":"Location change"}'::jsonb, 170),
        ('COMPENSATION_CHANGE', 'Compensation change', 'Compensation attributes changed without another staffing event.', '{"ko":"보상 변경","en":"Compensation change"}'::jsonb, 180),
        ('CONTRACT_CHANGE', 'Contract change', 'Employment or engagement contract terms changed.', '{"ko":"계약 변경","en":"Contract change"}'::jsonb, 190),
        ('CORRECTION', 'Correction', 'Source data was corrected without a new business event.', '{"ko":"정보 정정","en":"Correction"}'::jsonb, 200)
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

COMMENT ON TABLE ppl_assignment_change_reason_catalog IS
    'Tenant-extensible JML and assignment event vocabulary used by HRIS projections and governance workflows.';
