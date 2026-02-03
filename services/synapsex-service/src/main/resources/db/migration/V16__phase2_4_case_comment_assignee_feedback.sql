-- Phase2~4: case_comment, assignee, policy_suggestion, saved_view_key
-- SoT 확장

SET search_path TO dwp_aura, public;

-- 1) agent_case: assignee_user_id 추가
ALTER TABLE dwp_aura.agent_case
  ADD COLUMN IF NOT EXISTS assignee_user_id BIGINT,
  ADD COLUMN IF NOT EXISTS saved_view_key VARCHAR(100);

CREATE INDEX IF NOT EXISTS ix_agent_case_assignee
ON dwp_aura.agent_case(tenant_id, assignee_user_id) WHERE assignee_user_id IS NOT NULL;

-- 2) case_comment
CREATE TABLE IF NOT EXISTS dwp_aura.case_comment (
  comment_id    BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  case_id       BIGINT NOT NULL REFERENCES dwp_aura.agent_case(case_id) ON DELETE CASCADE,
  author_user_id BIGINT,
  author_agent_id VARCHAR(80),
  comment_text  TEXT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_case_comment_case
ON dwp_aura.case_comment(tenant_id, case_id, created_at DESC);

COMMENT ON TABLE dwp_aura.case_comment IS '케이스 코멘트.';

-- 3) policy_suggestion (feedback 확장)
CREATE TABLE IF NOT EXISTS dwp_aura.policy_suggestion (
  suggestion_id  BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  case_id       BIGINT REFERENCES dwp_aura.agent_case(case_id) ON DELETE SET NULL,
  suggested_action VARCHAR(100),
  suggested_rule  TEXT,
  comment       TEXT,
  created_by    BIGINT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  status        VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX IF NOT EXISTS ix_policy_suggestion_tenant
ON dwp_aura.policy_suggestion(tenant_id, created_at DESC);

COMMENT ON TABLE dwp_aura.policy_suggestion IS '정책 제안. feedback 확장.';

-- 4) feedback_label: correct_action 컬럼 추가
ALTER TABLE dwp_aura.feedback_label
  ADD COLUMN IF NOT EXISTS correct_action VARCHAR(100),
  ADD COLUMN IF NOT EXISTS case_id BIGINT REFERENCES dwp_aura.agent_case(case_id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS ix_feedback_label_case
ON dwp_aura.feedback_label(tenant_id, case_id) WHERE case_id IS NOT NULL;
