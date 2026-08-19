CREATE TEMP TABLE tmp_space_operations_code_contract (
    code_set_key VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    contract_kind VARCHAR(24) NOT NULL,
    source_reference VARCHAR(255) NOT NULL,
    allowed_values VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_space_operations_code_contract (
    code_set_key, display_name, description,
    contract_kind, source_reference, allowed_values)
VALUES
    ('SPACE.POLICY_EVALUATION.TYPE', 'Space policy evaluation type',
     'Policy control evaluated before a governed Space transition.',
     'SECURITY', 'spc_policy_evaluations.policy_type',
     ARRAY['CONTENT_PUBLICATION', 'LIFECYCLE', 'MEMBERSHIP_CHANGE',
           'SPACE_ACCESS', 'SPACE_CREATION']::VARCHAR[]),
    ('SPACE.POLICY_EVALUATION.SUBJECT_TYPE', 'Space policy subject type',
     'Subject governed by a recorded Space policy decision.',
     'SECURITY', 'spc_policy_evaluations.subject_type',
     ARRAY['ACCESS_REQUEST', 'CONTENT', 'MEMBERSHIP', 'SPACE', 'SPACE_REQUEST']::VARCHAR[]),
    ('SPACE.POLICY_EVALUATION.DECISION', 'Space policy decision',
     'Normalized outcome of a Space policy evaluation.',
     'STATE_MACHINE', 'spc_policy_evaluations.decision',
     ARRAY['ALLOW', 'BLOCK', 'DENY', 'REVIEW']::VARCHAR[]),
    ('SPACE.POLICY_EVALUATION.ENFORCEMENT_MODE', 'Space policy enforcement mode',
     'Authority or workflow responsible for enforcing a policy decision.',
     'SECURITY', 'spc_policy_evaluations.enforcement_mode',
     ARRAY['ADMIN_REVIEW', 'APPROVAL', 'AUTO', 'COMPLIANCE_REVIEW',
           'OPEN_PUBLISH', 'OWNER_REVIEW', 'POLICY', 'SYSTEM']::VARCHAR[]),
    ('SPACE.POLICY_EVALUATION.EVALUATOR_TYPE', 'Space policy evaluator type',
     'Human or automated actor producing policy evidence.',
     'OBSERVABILITY', 'spc_policy_evaluations.evaluator_type',
     ARRAY['AGENT', 'POLICY', 'SYSTEM', 'USER']::VARCHAR[]),
    ('SPACE.ENTITLEMENT.PERMISSION', 'Space entitlement permission',
     'Fine-grained permission projected from a Space membership role.',
     'SECURITY', 'spc_entitlement_sync_items.permission_code',
     ARRAY['APPROVE', 'CREATE', 'MANAGE', 'UPDATE', 'VIEW']::VARCHAR[]),
    ('SPACE.ENTITLEMENT.DESIRED_STATE', 'Space entitlement desired state',
     'Desired central IAG state derived from the Space membership source of truth.',
     'STATE_MACHINE', 'spc_entitlement_sync_items.desired_state',
     ARRAY['GRANTED', 'REVOKED']::VARCHAR[]),
    ('SPACE.ENTITLEMENT.DELIVERY_STATE', 'Space entitlement delivery state',
     'Retryable delivery lifecycle between Space and the identity governance plane.',
     'STATE_MACHINE', 'spc_entitlement_sync_items.delivery_state',
     ARRAY['DEAD', 'IN_PROGRESS', 'PENDING', 'RETRY', 'SUCCEEDED']::VARCHAR[]),
    ('SPACE.RECONCILIATION.TRIGGER_TYPE', 'Space reconciliation trigger',
     'Operator, schedule, or recovery trigger for desired-state reconciliation.',
     'OBSERVABILITY', 'spc_reconciliation_runs.trigger_type',
     ARRAY['MANUAL', 'RECOVERY', 'SCHEDULED']::VARCHAR[]),
    ('SPACE.RECONCILIATION.RUN_STATE', 'Space reconciliation run state',
     'Execution lifecycle of a Space reconciliation run.',
     'STATE_MACHINE', 'spc_reconciliation_runs.lifecycle_state',
     ARRAY['FAILED', 'RUNNING', 'SUCCEEDED']::VARCHAR[]),
    ('SPACE.RECONCILIATION.FINDING_TYPE', 'Space reconciliation finding type',
     'Operational drift class detected by Space reconciliation.',
     'OBSERVABILITY', 'spc_reconciliation_findings.finding_type',
     ARRAY['ENTITLEMENT_DELIVERY', 'EXPIRED_MEMBERSHIP',
           'LIFECYCLE_REVIEW', 'OWNERLESS_SPACE']::VARCHAR[]),
    ('SPACE.RECONCILIATION.SEVERITY', 'Space reconciliation severity',
     'Operational urgency assigned to a Space reconciliation finding.',
     'OBSERVABILITY', 'spc_reconciliation_findings.severity',
     ARRAY['CRITICAL', 'HIGH', 'INFO', 'WARNING']::VARCHAR[]),
    ('SPACE.RECONCILIATION.FINDING_STATE', 'Space reconciliation finding state',
     'Open, acknowledged, and automatically resolved finding lifecycle.',
     'STATE_MACHINE', 'spc_reconciliation_findings.lifecycle_state',
     ARRAY['ACKNOWLEDGED', 'OPEN', 'RESOLVED']::VARCHAR[]),
    ('SPACE.RECONCILIATION.TARGET_TYPE', 'Space reconciliation target type',
     'Operational object affected by a reconciliation finding.',
     'OBSERVABILITY', 'spc_reconciliation_findings.target_type',
     ARRAY['LIFECYCLE_REVIEW', 'MEMBERSHIP', 'SPACE', 'SYNC_ITEM']::VARCHAR[]);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
SELECT code_set_key, 'dwp-space-server', display_name, description,
       'SYSTEM', 'CHECK', source_reference, contract_kind, 'ADMIN_ONLY'
  FROM tmp_space_operations_code_contract
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    validation_source = EXCLUDED.validation_source,
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, lifecycle_state)
SELECT contract.code_set_key,
       value.code,
       INITCAP(REPLACE(LOWER(value.code), '_', ' ')),
       jsonb_build_object(
           'ko', CASE value.code
               WHEN 'ALLOW' THEN '허용'
               WHEN 'BLOCK' THEN '차단'
               WHEN 'DENY' THEN '거부'
               WHEN 'REVIEW' THEN '검토 필요'
               WHEN 'GRANTED' THEN '부여'
               WHEN 'REVOKED' THEN '회수'
               WHEN 'IN_PROGRESS' THEN '처리 중'
               WHEN 'PENDING' THEN '대기 중'
               WHEN 'RETRY' THEN '재시도'
               WHEN 'DEAD' THEN '처리 중단'
               WHEN 'SUCCEEDED' THEN '성공'
               WHEN 'FAILED' THEN '실패'
               WHEN 'RUNNING' THEN '실행 중'
               WHEN 'OPEN' THEN '열림'
               WHEN 'ACKNOWLEDGED' THEN '확인됨'
               WHEN 'RESOLVED' THEN '해결됨'
               WHEN 'CRITICAL' THEN '매우 높음'
               WHEN 'HIGH' THEN '높음'
               WHEN 'WARNING' THEN '주의'
               WHEN 'INFO' THEN '정보'
               ELSE INITCAP(REPLACE(LOWER(value.code), '_', ' '))
           END,
           'en', INITCAP(REPLACE(LOWER(value.code), '_', ' '))),
       value.ordinality * 10, '{}'::jsonb, 'ACTIVE'
  FROM tmp_space_operations_code_contract contract
 CROSS JOIN LATERAL unnest(contract.allowed_values)
      WITH ORDINALITY AS value(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT code_set_key, 'dwp-space-server', 'DATABASE_COLUMN',
       source_reference, 'CHECK', 'ACTIVE'
  FROM tmp_space_operations_code_contract
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO UPDATE SET
    enforcement_type = 'CHECK', lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
VALUES
    ('SPACE.CREATION_REQUEST.RISK_LEVEL', 'dwp-space-server', 'DATABASE_COLUMN',
     'spc_policy_evaluations.risk_level', 'CHECK', 'ACTIVE'),
    ('SPACE.PRINCIPAL.TYPE', 'dwp-space-server', 'DATABASE_COLUMN',
     'spc_entitlement_sync_items.principal_type', 'CHECK', 'ACTIVE')
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO UPDATE SET
    enforcement_type = 'CHECK', lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
