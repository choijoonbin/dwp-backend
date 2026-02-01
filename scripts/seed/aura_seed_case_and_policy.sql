-- Aura 시드: config/profile + agent_case (CSV)
-- 스키마: dwp_aura (V3/V4)
-- 실행: ./scripts/run-aura-seed.sh [CSV디렉터리]
--   예: ./scripts/run-aura-seed.sh /Users/joonbinchoi/Downloads
-- 변수: :tenant_id (기본 1), :csvpath (agent_case_seed.csv 전체 경로)

SET search_path TO dwp_aura, public;

-- [1] config_profile 1건 → profile_id 확보 (\gset)
INSERT INTO dwp_aura.config_profile (tenant_id, profile_name, description, is_default)
VALUES (:tenant_id, 'Default', '기본 정책(개발 초기)', true)
ON CONFLICT (tenant_id, profile_name) DO UPDATE SET description = EXCLUDED.description
RETURNING profile_id \gset

-- [2] config_kv 최소 seed
INSERT INTO dwp_aura.config_kv (tenant_id, profile_id, config_key, config_value)
VALUES
  (:tenant_id, :profile_id, 'duplicate.amount_tolerance_pct', '0.01'::jsonb),
  (:tenant_id, :profile_id, 'duplicate.date_tolerance_days', '3'::jsonb),
  (:tenant_id, :profile_id, 'burst.window_minutes', '5'::jsonb),
  (:tenant_id, :profile_id, 'burst.threshold_docs', '20'::jsonb)
ON CONFLICT (tenant_id, profile_id, config_key) DO UPDATE SET config_value = EXCLUDED.config_value, updated_at = now();

-- [3] rule_duplicate_invoice 1건
INSERT INTO dwp_aura.rule_duplicate_invoice (
  tenant_id, profile_id, rule_name, is_enabled,
  key_fields, amount_tolerance_pct, date_tolerance_days,
  severity_on_match, action_on_match
)
VALUES (
  :tenant_id, :profile_id, 'dup_default', true,
  '["vendor","xblnr","currency"]'::jsonb, 0.01, 3,
  'HIGH', 'SET_PAYMENT_BLOCK_AND_NOTIFY'
)
ON CONFLICT (tenant_id, profile_id, rule_name) DO UPDATE SET is_enabled = EXCLUDED.is_enabled, updated_at = now();

-- [4] rule_threshold 1건
INSERT INTO dwp_aura.rule_threshold (
  tenant_id, profile_id, dimension, dimension_key, waers,
  threshold_amount, require_evidence, severity_on_breach, action_on_breach
)
VALUES (
  :tenant_id, :profile_id, 'ACCOUNT', '510030', 'KRW',
  500000, true, 'MEDIUM', 'FLAG_FOR_REVIEW'
)
ON CONFLICT (tenant_id, profile_id, dimension, dimension_key, waers) DO UPDATE SET threshold_amount = EXCLUDED.threshold_amount, updated_at = now();

-- [5] policy_action_guardrail (LOW/MEDIUM/HIGH)
INSERT INTO dwp_aura.policy_action_guardrail (tenant_id, profile_id, severity, allow_actions, require_human_approval)
VALUES
  (:tenant_id, :profile_id, 'LOW',    '["LOG_ONLY","FLAG_FOR_REVIEW"]'::jsonb, false),
  (:tenant_id, :profile_id, 'MEDIUM', '["FLAG_FOR_REVIEW","CREATE_TICKET","SEND_NUDGE_AND_CREATE_TICKET"]'::jsonb, true),
  (:tenant_id, :profile_id, 'HIGH',   '["SET_PAYMENT_BLOCK_AND_NOTIFY","CREATE_TICKET"]'::jsonb, true)
ON CONFLICT (tenant_id, profile_id, severity) DO UPDATE SET allow_actions = EXCLUDED.allow_actions, updated_at = now();

-- [6] policy_notification_channel
INSERT INTO dwp_aura.policy_notification_channel (tenant_id, profile_id, channel_type, is_enabled, config_json)
VALUES
  (:tenant_id, :profile_id, 'PORTAL', true, '{"template":"DEFAULT"}'::jsonb),
  (:tenant_id, :profile_id, 'WEBHOOK', false, '{"endpoint":"https://example.invalid/webhook","timeout":3000}'::jsonb)
ON CONFLICT (tenant_id, profile_id, channel_type) DO UPDATE SET is_enabled = EXCLUDED.is_enabled, updated_at = now();

-- [7] policy_pii_field
INSERT INTO dwp_aura.policy_pii_field (tenant_id, profile_id, field_name, handling, note)
VALUES
  (:tenant_id, :profile_id, 'bp_party.name_display', 'ALLOW', '개발 초기'),
  (:tenant_id, :profile_id, 'bp_party_pii_vault.bank_account', 'ENCRYPT', '운영전환 시 암호화')
ON CONFLICT (tenant_id, profile_id, field_name) DO UPDATE SET handling = EXCLUDED.handling, updated_at = now();

-- [8] agent_case: 스테이징 테이블 + CSV 로드
DROP TABLE IF EXISTS _stg_agent_case_seed;
CREATE TEMP TABLE _stg_agent_case_seed (
  tenant_id BIGINT,
  bukrs TEXT,
  belnr TEXT,
  gjahr TEXT,
  buzei TEXT,
  case_type TEXT,
  severity TEXT,
  recommended_action TEXT,
  reason_text TEXT,
  evidence_json TEXT
);

-- CSV 경로는 run-aura-seed.sh에서 __CSVPATH__ 로 치환됨
\copy _stg_agent_case_seed (tenant_id, bukrs, belnr, gjahr, buzei, case_type, severity, recommended_action, reason_text, evidence_json) FROM '__CSVPATH__' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

INSERT INTO dwp_aura.agent_case (
  tenant_id, bukrs, belnr, gjahr, buzei,
  case_type, severity, reason_text, evidence_json,
  status, detected_at, created_at, updated_at
)
SELECT
  s.tenant_id, s.bukrs, s.belnr, s.gjahr, s.buzei,
  s.case_type, s.severity, s.reason_text, s.evidence_json::jsonb,
  'OPEN'::dwp_aura.agent_case_status, now(), now(), now()
FROM _stg_agent_case_seed s
WHERE s.case_type <> 'NORMAL'
  AND NOT EXISTS (
    SELECT 1 FROM dwp_aura.agent_case c
    WHERE c.tenant_id = s.tenant_id AND c.bukrs = s.bukrs AND c.belnr = s.belnr
      AND c.gjahr = s.gjahr AND c.buzei = s.buzei AND c.case_type = s.case_type
  );

\echo 'Aura seed complete: config/policy + agent_case from CSV'
