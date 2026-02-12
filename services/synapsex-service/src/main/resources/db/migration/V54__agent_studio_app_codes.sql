-- 에이전트 스튜디오 전용 코드: dwp_aura.app_code_groups / app_codes
-- AGENT_DOMAIN, DOC_TYPE(청킹용), LLM_MODEL

SET search_path TO dwp_aura, public;

-- AGENT_DOMAIN
INSERT INTO dwp_aura.app_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES ('AGENT_DOMAIN', '에이전트 업무 영역', '에이전트 도메인 (FINANCE, HR, DEV)', true, now(), now())
ON CONFLICT (group_key) DO UPDATE SET group_name = EXCLUDED.group_name, description = EXCLUDED.description, updated_at = now();

INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('AGENT_DOMAIN', 'FINANCE', '재무 감사', '재무/감사 영역', 10, true, now(), now()),
  ('AGENT_DOMAIN', 'HR', '인사', '인사 영역', 20, true, now(), now()),
  ('AGENT_DOMAIN', 'DEV', '개발', '개발/엔지니어링 영역', 30, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();

-- DOC_TYPE (청킹 전략용: HIERARCHICAL, SEQUENTIAL, POLICY, GENERAL)
INSERT INTO dwp_aura.app_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES ('DOC_TYPE', '문서 유형', '청킹 전략용 문서 타입', true, now(), now())
ON CONFLICT (group_key) DO UPDATE SET group_name = EXCLUDED.group_name, description = EXCLUDED.description, updated_at = now();

INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('DOC_TYPE', 'HIERARCHICAL', '계층형(규정집)', '조/항 구조', 10, true, now(), now()),
  ('DOC_TYPE', 'SEQUENTIAL', '순차형', '순차 문서', 20, true, now(), now()),
  ('DOC_TYPE', 'POLICY', '정책', '정책 문서', 30, true, now(), now()),
  ('DOC_TYPE', 'GENERAL', '일반', '일반 문서', 40, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();

-- LLM_MODEL
INSERT INTO dwp_aura.app_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES ('LLM_MODEL', 'LLM 모델', '사용 가능 엔진', true, now(), now())
ON CONFLICT (group_key) DO UPDATE SET group_name = EXCLUDED.group_name, description = EXCLUDED.description, updated_at = now();

INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('LLM_MODEL', 'gpt-4o', 'GPT-4o (High Speed)', 'OpenAI GPT-4o', 10, true, now(), now()),
  ('LLM_MODEL', 'claude-3-5-sonnet', 'Claude 3.5 Sonnet', 'Anthropic Claude 3.5 Sonnet', 20, true, now(), now()),
  ('LLM_MODEL', 'r1', 'R1', 'Reasoning model', 30, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();
