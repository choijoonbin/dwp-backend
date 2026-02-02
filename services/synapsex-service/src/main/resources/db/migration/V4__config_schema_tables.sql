-- ======================================================================
-- Self-healing Finance - Config/Policy Schema v1 (Delta)
-- 스키마: dwp_aura (DWP Aura Service 전용)
-- 전제: com_tenants(tenant_id=1)는 dwp-auth DB에 존재, tenant_id는 X-Tenant-ID(BIGINT) 기준
-- 시스템 컬럼: created_at, created_by, updated_at, updated_by 디폴트 포함 + COMMENT
-- ======================================================================

SET search_path TO dwp_aura, public;

-- ======================================================================
-- 1) 설정 프로파일(정책 세트)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.config_profile (
  profile_id      BIGSERIAL PRIMARY KEY,
  tenant_id       BIGINT NOT NULL,
  profile_name   TEXT NOT NULL,
  description    TEXT,
  is_default     BOOLEAN NOT NULL DEFAULT false,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by     BIGINT,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by     BIGINT,
  UNIQUE (tenant_id, profile_name)
);

CREATE INDEX IF NOT EXISTS ix_config_profile_tenant_id ON dwp_aura.config_profile(tenant_id);
CREATE INDEX IF NOT EXISTS ix_config_profile_default ON dwp_aura.config_profile(tenant_id, is_default);

COMMENT ON TABLE dwp_aura.config_profile IS '설정 프로파일(정책 세트). 고객사별 default/strict/pilot 등 운영 중 스위칭/AB 테스트용.';
COMMENT ON COLUMN dwp_aura.config_profile.profile_id IS '프로파일 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.config_profile.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN dwp_aura.config_profile.profile_name IS '프로파일명';
COMMENT ON COLUMN dwp_aura.config_profile.description IS '프로파일 설명';
COMMENT ON COLUMN dwp_aura.config_profile.is_default IS '테넌트 기본 프로파일 여부';
COMMENT ON COLUMN dwp_aura.config_profile.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.config_profile.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.config_profile.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.config_profile.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ======================================================================
-- 2) Key-Value 설정(빠른 확장용)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.config_kv (
  tenant_id       BIGINT NOT NULL,
  profile_id      BIGINT NOT NULL REFERENCES dwp_aura.config_profile(profile_id) ON DELETE CASCADE,
  config_key      TEXT NOT NULL,
  config_value    JSONB NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      BIGINT,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      BIGINT,
  PRIMARY KEY (tenant_id, profile_id, config_key)
);

CREATE INDEX IF NOT EXISTS ix_config_kv_tenant_id ON dwp_aura.config_kv(tenant_id);

COMMENT ON TABLE dwp_aura.config_kv IS 'Key-Value 설정. 규정/정책 추가 시 컬럼 확장 없이 확장용.';
COMMENT ON COLUMN dwp_aura.config_kv.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN dwp_aura.config_kv.profile_id IS '프로파일 식별자 (논리적 참조: config_profile.profile_id)';
COMMENT ON COLUMN dwp_aura.config_kv.config_key IS '설정 키';
COMMENT ON COLUMN dwp_aura.config_kv.config_value IS '설정 값 (JSONB)';
COMMENT ON COLUMN dwp_aura.config_kv.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.config_kv.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.config_kv.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.config_kv.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ======================================================================
-- 3) 중복송장 정의(룰셋)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.rule_duplicate_invoice (
  rule_id               BIGSERIAL PRIMARY KEY,
  tenant_id              BIGINT NOT NULL,
  profile_id             BIGINT NOT NULL REFERENCES dwp_aura.config_profile(profile_id) ON DELETE CASCADE,
  rule_name              TEXT NOT NULL,
  is_enabled             BOOLEAN NOT NULL DEFAULT true,
  key_fields             JSONB NOT NULL,
  amount_tolerance_pct   NUMERIC(6,3) NOT NULL DEFAULT 0,
  date_tolerance_days    INT NOT NULL DEFAULT 0,
  split_window_days      INT NOT NULL DEFAULT 0,
  split_count_threshold  INT NOT NULL DEFAULT 0,
  split_amount_threshold NUMERIC(18,2) NOT NULL DEFAULT 0,
  severity_on_match      TEXT NOT NULL DEFAULT 'HIGH',
  action_on_match        TEXT NOT NULL DEFAULT 'SET_PAYMENT_BLOCK_AND_NOTIFY',
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by             BIGINT,
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by             BIGINT,
  UNIQUE (tenant_id, profile_id, rule_name)
);

CREATE INDEX IF NOT EXISTS ix_rule_duplicate_invoice_tenant_id ON dwp_aura.rule_duplicate_invoice(tenant_id);
CREATE INDEX IF NOT EXISTS ix_rule_duplicate_invoice_enabled ON dwp_aura.rule_duplicate_invoice(tenant_id, profile_id, is_enabled);

COMMENT ON TABLE dwp_aura.rule_duplicate_invoice IS '중복송장 정의(룰셋). 회사/고객사별 중복 정의 옵션화.';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.rule_id IS '룰 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.profile_id IS '프로파일 식별자 (논리적 참조: config_profile.profile_id)';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.rule_name IS '룰명';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.is_enabled IS '활성 여부';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.key_fields IS '중복 판단 키 필드 세트(JSON 배열, 예: lifnr,xblnr,waers,wrbtr)';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.amount_tolerance_pct IS '금액 허용 오차(%)';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.date_tolerance_days IS '날짜 허용 오차(일)';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.split_window_days IS 'split 회피 탐지: 기간(일)';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.split_count_threshold IS 'split 회피 탐지: 건수 임계치';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.split_amount_threshold IS 'split 회피 탐지: 금액 임계치';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.severity_on_match IS '매칭 시 심각도';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.action_on_match IS '매칭 시 조치';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.rule_duplicate_invoice.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ======================================================================
-- 4) 한도/정책(계정/카테고리/코스트센터 등)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.rule_threshold (
  threshold_id       BIGSERIAL PRIMARY KEY,
  tenant_id          BIGINT NOT NULL,
  profile_id         BIGINT NOT NULL REFERENCES dwp_aura.config_profile(profile_id) ON DELETE CASCADE,
  policy_doc_id      TEXT,
  dimension          TEXT NOT NULL,
  dimension_key      TEXT NOT NULL,
  waers              TEXT NOT NULL DEFAULT 'KRW',
  threshold_amount   NUMERIC(18,2) NOT NULL,
  require_evidence   BOOLEAN NOT NULL DEFAULT false,
  evidence_types     JSONB,
  severity_on_breach TEXT NOT NULL DEFAULT 'MEDIUM',
  action_on_breach   TEXT NOT NULL DEFAULT 'FLAG_FOR_REVIEW',
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by         BIGINT,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by         BIGINT,
  UNIQUE (tenant_id, profile_id, dimension, dimension_key, waers)
);

CREATE INDEX IF NOT EXISTS ix_rule_threshold_tenant_id ON dwp_aura.rule_threshold(tenant_id);
CREATE INDEX IF NOT EXISTS ix_rule_threshold_dim ON dwp_aura.rule_threshold(tenant_id, profile_id, dimension, dimension_key);

COMMENT ON TABLE dwp_aura.rule_threshold IS '한도/정책(계정·카테고리·코스트센터 등). 예: 접대비 50만원 이상 증빙 필수.';
COMMENT ON COLUMN dwp_aura.rule_threshold.threshold_id IS '한도 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.rule_threshold.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN dwp_aura.rule_threshold.profile_id IS '프로파일 식별자 (논리적 참조: config_profile.profile_id)';
COMMENT ON COLUMN dwp_aura.rule_threshold.policy_doc_id IS 'RAG 정책 문서 연결(선택, 예: finance_compliance_docs)';
COMMENT ON COLUMN dwp_aura.rule_threshold.dimension IS '차원(HKONT|CATEGORY|COSTCENTER 등)';
COMMENT ON COLUMN dwp_aura.rule_threshold.dimension_key IS '차원 키(예: 510030)';
COMMENT ON COLUMN dwp_aura.rule_threshold.waers IS '통화 코드';
COMMENT ON COLUMN dwp_aura.rule_threshold.threshold_amount IS '한도 금액';
COMMENT ON COLUMN dwp_aura.rule_threshold.require_evidence IS '증빙 필수 여부';
COMMENT ON COLUMN dwp_aura.rule_threshold.evidence_types IS '증빙 유형(JSON 배열, 예: receipt,attendee_list)';
COMMENT ON COLUMN dwp_aura.rule_threshold.severity_on_breach IS '위반 시 심각도';
COMMENT ON COLUMN dwp_aura.rule_threshold.action_on_breach IS '위반 시 조치';
COMMENT ON COLUMN dwp_aura.rule_threshold.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.rule_threshold.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.rule_threshold.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.rule_threshold.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ======================================================================
-- 5) 조치 정책(Severity별 허용 조치/인가 승인)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.policy_action_guardrail (
  guardrail_id           BIGSERIAL PRIMARY KEY,
  tenant_id               BIGINT NOT NULL,
  profile_id              BIGINT NOT NULL REFERENCES dwp_aura.config_profile(profile_id) ON DELETE CASCADE,
  severity                TEXT NOT NULL,
  allow_actions           JSONB NOT NULL,
  require_human_approval  BOOLEAN NOT NULL DEFAULT true,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by              BIGINT,
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by              BIGINT,
  UNIQUE (tenant_id, profile_id, severity)
);

CREATE INDEX IF NOT EXISTS ix_policy_action_guardrail_tenant_id ON dwp_aura.policy_action_guardrail(tenant_id);

COMMENT ON TABLE dwp_aura.policy_action_guardrail IS '조치 정책(Severity별 허용 조치·인가 승인). Agentic AI 가드레일.';
COMMENT ON COLUMN dwp_aura.policy_action_guardrail.guardrail_id IS '가드레일 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.policy_action_guardrail.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN dwp_aura.policy_action_guardrail.profile_id IS '프로파일 식별자 (논리적 참조: config_profile.profile_id)';
COMMENT ON COLUMN dwp_aura.policy_action_guardrail.severity IS '심각도(LOW/MEDIUM/HIGH)';
COMMENT ON COLUMN dwp_aura.policy_action_guardrail.allow_actions IS '허용 조치 목록(JSON 배열, 예: SEND_NUDGE,CREATE_TICKET)';
COMMENT ON COLUMN dwp_aura.policy_action_guardrail.require_human_approval IS '인가 승인 필수 여부';
COMMENT ON COLUMN dwp_aura.policy_action_guardrail.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.policy_action_guardrail.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.policy_action_guardrail.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.policy_action_guardrail.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ======================================================================
-- 6) 알림 채널 정책(고객사별 옵션)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.policy_notification_channel (
  channel_id     BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  profile_id     BIGINT NOT NULL REFERENCES dwp_aura.config_profile(profile_id) ON DELETE CASCADE,
  channel_type   TEXT NOT NULL,
  is_enabled     BOOLEAN NOT NULL DEFAULT true,
  config_json    JSONB NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by     BIGINT,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by     BIGINT,
  UNIQUE (tenant_id, profile_id, channel_type)
);

CREATE INDEX IF NOT EXISTS ix_policy_notification_channel_tenant_id ON dwp_aura.policy_notification_channel(tenant_id);

COMMENT ON TABLE dwp_aura.policy_notification_channel IS '알림 채널 정책(고객사별 옵션). EMAIL|SMS|MESSENGER|PORTAL|WEBHOOK 등.';
COMMENT ON COLUMN dwp_aura.policy_notification_channel.channel_id IS '채널 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.policy_notification_channel.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN dwp_aura.policy_notification_channel.profile_id IS '프로파일 식별자 (논리적 참조: config_profile.profile_id)';
COMMENT ON COLUMN dwp_aura.policy_notification_channel.channel_type IS '채널 유형(EMAIL|SMS|MESSENGER|PORTAL|WEBHOOK)';
COMMENT ON COLUMN dwp_aura.policy_notification_channel.is_enabled IS '활성 여부';
COMMENT ON COLUMN dwp_aura.policy_notification_channel.config_json IS '채널 설정(endpoint, auth, templates, throttle 등 JSON)';
COMMENT ON COLUMN dwp_aura.policy_notification_channel.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.policy_notification_channel.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.policy_notification_channel.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.policy_notification_channel.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ======================================================================
-- 7) PII 정책(필드별 저장 정책)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.policy_pii_field (
  pii_id         BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  profile_id     BIGINT NOT NULL REFERENCES dwp_aura.config_profile(profile_id) ON DELETE CASCADE,
  field_name     TEXT NOT NULL,
  handling       TEXT NOT NULL,
  note           TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by     BIGINT,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by     BIGINT,
  UNIQUE (tenant_id, profile_id, field_name)
);

CREATE INDEX IF NOT EXISTS ix_policy_pii_field_tenant_id ON dwp_aura.policy_pii_field(tenant_id);

COMMENT ON TABLE dwp_aura.policy_pii_field IS 'PII 정책(필드별 저장 정책). 운영 전환 시 ALLOW→ENCRYPT/HASH_ONLY 등 강화 가능.';
COMMENT ON COLUMN dwp_aura.policy_pii_field.pii_id IS 'PII 정책 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.policy_pii_field.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN dwp_aura.policy_pii_field.profile_id IS '프로파일 식별자 (논리적 참조: config_profile.profile_id)';
COMMENT ON COLUMN dwp_aura.policy_pii_field.field_name IS '필드명';
COMMENT ON COLUMN dwp_aura.policy_pii_field.handling IS '저장 정책(ALLOW|MASK|HASH_ONLY|ENCRYPT|FORBID)';
COMMENT ON COLUMN dwp_aura.policy_pii_field.note IS '비고';
COMMENT ON COLUMN dwp_aura.policy_pii_field.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.policy_pii_field.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.policy_pii_field.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.policy_pii_field.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ======================================================================
-- Seed (샘플) - tenant_id=1 은 com_tenants.tenant_id=1(dev) 기준 통일
-- ======================================================================
INSERT INTO dwp_aura.config_profile(tenant_id, profile_name, description, is_default)
VALUES (1, 'default_profile', '기본 정책 프로파일(샘플)', true)
ON CONFLICT (tenant_id, profile_name) DO NOTHING;

WITH p AS (
  SELECT profile_id FROM dwp_aura.config_profile WHERE tenant_id = 1 AND profile_name = 'default_profile'
)
INSERT INTO dwp_aura.rule_duplicate_invoice(
  tenant_id, profile_id, rule_name, key_fields,
  amount_tolerance_pct, date_tolerance_days,
  severity_on_match, action_on_match
)
SELECT 1, p.profile_id, 'dup_basic',
       '["lifnr","xblnr","waers","wrbtr"]'::jsonb,
       1.0, 3,
       'HIGH', 'SET_PAYMENT_BLOCK_AND_NOTIFY'
FROM p
ON CONFLICT (tenant_id, profile_id, rule_name) DO NOTHING;

WITH p AS (
  SELECT profile_id FROM dwp_aura.config_profile WHERE tenant_id = 1 AND profile_name = 'default_profile'
)
INSERT INTO dwp_aura.policy_action_guardrail(tenant_id, profile_id, severity, allow_actions, require_human_approval)
SELECT 1, p.profile_id, 'HIGH', '["SET_PAYMENT_BLOCK","PARK_DOC","CREATE_TICKET","SEND_NUDGE"]'::jsonb, true
FROM p
ON CONFLICT (tenant_id, profile_id, severity) DO NOTHING;

WITH p AS (
  SELECT profile_id FROM dwp_aura.config_profile WHERE tenant_id = 1 AND profile_name = 'default_profile'
)
INSERT INTO dwp_aura.policy_action_guardrail(tenant_id, profile_id, severity, allow_actions, require_human_approval)
SELECT 1, p.profile_id, 'MEDIUM', '["CREATE_TICKET","SEND_NUDGE","FLAG_FOR_REVIEW"]'::jsonb, true
FROM p
ON CONFLICT (tenant_id, profile_id, severity) DO NOTHING;

WITH p AS (
  SELECT profile_id FROM dwp_aura.config_profile WHERE tenant_id = 1 AND profile_name = 'default_profile'
)
INSERT INTO dwp_aura.policy_action_guardrail(tenant_id, profile_id, severity, allow_actions, require_human_approval)
SELECT 1, p.profile_id, 'LOW', '["LOG_ONLY","SEND_NUDGE"]'::jsonb, false
FROM p
ON CONFLICT (tenant_id, profile_id, severity) DO NOTHING;

WITH p AS (
  SELECT profile_id FROM dwp_aura.config_profile WHERE tenant_id = 1 AND profile_name = 'default_profile'
)
INSERT INTO dwp_aura.policy_notification_channel(tenant_id, profile_id, channel_type, is_enabled, config_json)
SELECT 1, p.profile_id, 'WEBHOOK', true,
       '{"endpoint":"https://example.com/webhook","auth":"optional","template":"default"}'::jsonb
FROM p
ON CONFLICT (tenant_id, profile_id, channel_type) DO NOTHING;

WITH p AS (
  SELECT profile_id FROM dwp_aura.config_profile WHERE tenant_id = 1 AND profile_name = 'default_profile'
)
INSERT INTO dwp_aura.policy_pii_field(tenant_id, profile_id, field_name, handling, note)
SELECT 1, p.profile_id, 'bp_party_pii_vault.pii_cipher', 'ALLOW',
       '초기 개발 단계: 전체 오픈(운영 전환 시 ENCRYPT/HASH_ONLY 권장)'
FROM p
ON CONFLICT (tenant_id, profile_id, field_name) DO NOTHING;
