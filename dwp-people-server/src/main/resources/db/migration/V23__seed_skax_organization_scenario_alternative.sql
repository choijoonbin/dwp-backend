-- Keep a meaningful alternative in every local environment so scenario
-- lineage and side-by-side decision comparison are testable after a rebuild.
INSERT INTO ppl_organization_scenarios (
    organization_scenario_id, tenant_id, scenario_key, name, description,
    baseline_date, effective_date, baseline_fingerprint, lifecycle_state,
    owner_user_id, source_scenario_id, version, created_by, updated_by)
SELECT md5('skax-scenario:ai-scale-up-2027-growth')::uuid,
       source.tenant_id,
       'ai-scale-up-2027-growth',
       'AI Scale-up 2027 · Growth Alternative',
       'GenAI 거버넌스 리더십과 핵심 직위 투자를 포함한 성장 대안',
       source.baseline_date,
       source.effective_date,
       source.baseline_fingerprint,
       'DRAFT',
       1,
       source.organization_scenario_id,
       1,
       1,
       1
  FROM ppl_organization_scenarios source
 WHERE source.tenant_id = 1
   AND source.scenario_key = 'ai-scale-up-2027'
ON CONFLICT (tenant_id, scenario_key) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    baseline_date = EXCLUDED.baseline_date,
    effective_date = EXCLUDED.effective_date,
    baseline_fingerprint = EXCLUDED.baseline_fingerprint,
    source_scenario_id = EXCLUDED.source_scenario_id,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_organization_scenario_changes (
    organization_scenario_change_id, tenant_id, organization_scenario_id,
    change_sequence, change_type, payload_schema_version, target_kind,
    target_reference, related_reference, effective_date,
    before_snapshot, after_snapshot, estimated_headcount_delta,
    estimated_fte_delta, estimated_cost_delta, cost_currency,
    validation_state, validation_message, created_by, updated_by)
SELECT md5('skax-scenario-change:ai-scale-up-2027-growth:cx')::uuid,
       alternative.tenant_id,
       alternative.organization_scenario_id,
       1,
       source_change.change_type,
       source_change.payload_schema_version,
       source_change.target_kind,
       source_change.target_reference,
       source_change.related_reference,
       alternative.effective_date,
       source_change.before_snapshot,
       source_change.after_snapshot,
       source_change.estimated_headcount_delta,
       source_change.estimated_fte_delta,
       source_change.estimated_cost_delta,
       source_change.cost_currency,
       source_change.validation_state,
       source_change.validation_message,
       1,
       1
  FROM ppl_organization_scenarios alternative
  JOIN ppl_organization_scenarios source
    ON source.tenant_id = alternative.tenant_id
   AND source.organization_scenario_id = alternative.source_scenario_id
  JOIN ppl_organization_scenario_changes source_change
    ON source_change.tenant_id = source.tenant_id
   AND source_change.organization_scenario_id = source.organization_scenario_id
   AND source_change.change_sequence = 1
 WHERE alternative.tenant_id = 1
   AND alternative.scenario_key = 'ai-scale-up-2027-growth'
ON CONFLICT (organization_scenario_id, change_sequence) DO NOTHING;

INSERT INTO ppl_organization_scenario_changes (
    organization_scenario_change_id, tenant_id, organization_scenario_id,
    change_sequence, change_type, payload_schema_version, target_kind,
    target_reference, related_reference, effective_date,
    before_snapshot, after_snapshot, estimated_headcount_delta,
    estimated_fte_delta, estimated_cost_delta, cost_currency,
    validation_state, validation_message, created_by, updated_by)
SELECT md5('skax-scenario-change:ai-scale-up-2027-growth:ai-governance')::uuid,
       alternative.tenant_id,
       alternative.organization_scenario_id,
       2,
       'CREATE_POSITION',
       catalog.payload_schema_version,
       'POSITION',
       md5('skax-scenario-position:ai-scale-up-2027-growth:ai-governance')::uuid::text,
       parent.public_id::text,
       alternative.effective_date,
       '{}'::jsonb,
       jsonb_build_object(
           'positionKey', 'PLAN-AI-GOV-2027',
           'title', 'AI Governance Lead',
           'organizationId', organization.public_id::text,
           'organizationName', organization.name,
           'reportsToPositionId', parent.public_id::text,
           'reportsToPositionTitle', parent.title,
           'positionType', 'REGULAR',
           'criticality', 'CRITICAL',
           'budgetedFte', 2.0,
           'annualCostAmount', 360000000,
           'costCurrency', 'KRW',
           'availabilityDate', alternative.effective_date::text),
       0,
       2.0,
       360000000,
       'KRW',
       'VALID',
       '성장 대안 비교를 위한 핵심 AI 거버넌스 직위 계획',
       1,
       1
  FROM ppl_organization_scenarios alternative
  JOIN ppl_organization_change_type_catalog catalog
    ON catalog.change_type = 'CREATE_POSITION'
  JOIN ppl_organizations organization
    ON organization.tenant_id = alternative.tenant_id
   AND organization.organization_key = 'ORG-GENAI'
  JOIN ppl_positions parent
    ON parent.tenant_id = alternative.tenant_id
   AND parent.position_key = 'POS-SK0009'
 WHERE alternative.tenant_id = 1
   AND alternative.scenario_key = 'ai-scale-up-2027-growth'
ON CONFLICT (organization_scenario_id, change_sequence) DO UPDATE SET
    payload_schema_version = EXCLUDED.payload_schema_version,
    target_reference = EXCLUDED.target_reference,
    related_reference = EXCLUDED.related_reference,
    effective_date = EXCLUDED.effective_date,
    before_snapshot = EXCLUDED.before_snapshot,
    after_snapshot = EXCLUDED.after_snapshot,
    estimated_headcount_delta = EXCLUDED.estimated_headcount_delta,
    estimated_fte_delta = EXCLUDED.estimated_fte_delta,
    estimated_cost_delta = EXCLUDED.estimated_cost_delta,
    cost_currency = EXCLUDED.cost_currency,
    validation_state = EXCLUDED.validation_state,
    validation_message = EXCLUDED.validation_message,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;
