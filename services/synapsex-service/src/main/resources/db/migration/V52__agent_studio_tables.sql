-- ======================================================================
-- Agent Studio: 에이전트 마스터, 프롬프트 이력, 도구 인벤토리/매핑, 지식베이스 마스터
-- 스키마: dwp_aura
-- Tenant Isolation: agent_master, knowledge_base_master는 tenant_id 필수.
-- agent_tool_inventory는 전역 카탈로그(tool_name unique). agent_tool_mapping은 agent 기준 격리.
-- ======================================================================

SET search_path TO dwp_aura, public;

-- ----------------------------------------------------------------------
-- 1) agent_master
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dwp_aura.agent_master (
  agent_id     BIGSERIAL PRIMARY KEY,
  tenant_id    BIGINT NOT NULL,
  name         VARCHAR(255) NOT NULL,
  domain       VARCHAR(100),
  model_name   VARCHAR(255),
  temperature  NUMERIC(5,4),
  max_tokens   INT,
  is_active    BOOLEAN NOT NULL DEFAULT true,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_agent_master_tenant_id ON dwp_aura.agent_master(tenant_id);
CREATE INDEX IF NOT EXISTS ix_agent_master_tenant_active ON dwp_aura.agent_master(tenant_id, is_active);

COMMENT ON TABLE dwp_aura.agent_master IS '에이전트 스튜디오: 에이전트 마스터. Aura 런타임 조립용.';
COMMENT ON COLUMN dwp_aura.agent_master.agent_id IS '에이전트 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.agent_master.tenant_id IS '테넌트 식별자 (격리 필수)';
COMMENT ON COLUMN dwp_aura.agent_master.name IS '에이전트 표시명';
COMMENT ON COLUMN dwp_aura.agent_master.domain IS '도메인(예: FINANCE, COMPLIANCE)';
COMMENT ON COLUMN dwp_aura.agent_master.model_name IS 'LLM 모델명';
COMMENT ON COLUMN dwp_aura.agent_master.temperature IS '생성 온도';
COMMENT ON COLUMN dwp_aura.agent_master.max_tokens IS '최대 토큰';
COMMENT ON COLUMN dwp_aura.agent_master.is_active IS '활성 여부';

-- ----------------------------------------------------------------------
-- 2) agent_prompt_history
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dwp_aura.agent_prompt_history (
  prompt_id           BIGSERIAL PRIMARY KEY,
  agent_id            BIGINT NOT NULL REFERENCES dwp_aura.agent_master(agent_id) ON DELETE CASCADE,
  system_instruction  TEXT NOT NULL,
  version             INT NOT NULL,
  is_current          BOOLEAN NOT NULL DEFAULT false,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_agent_prompt_history_agent_id ON dwp_aura.agent_prompt_history(agent_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_agent_prompt_history_agent_version
  ON dwp_aura.agent_prompt_history(agent_id, version);

COMMENT ON TABLE dwp_aura.agent_prompt_history IS '에이전트 스튜디오: 시스템 프롬프트 버전 이력.';
COMMENT ON COLUMN dwp_aura.agent_prompt_history.prompt_id IS '프롬프트 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.agent_prompt_history.agent_id IS '에이전트 (FK)';
COMMENT ON COLUMN dwp_aura.agent_prompt_history.system_instruction IS '시스템 지시문 (텍스트)';
COMMENT ON COLUMN dwp_aura.agent_prompt_history.version IS '버전 번호';
COMMENT ON COLUMN dwp_aura.agent_prompt_history.is_current IS '현재 사용 중인 버전 여부';

-- ----------------------------------------------------------------------
-- 3) agent_tool_inventory (전역 카탈로그; Aura FINANCE_TOOLS와 tool_name 일치)
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dwp_aura.agent_tool_inventory (
  tool_id      BIGSERIAL PRIMARY KEY,
  tool_name    VARCHAR(255) NOT NULL,
  description  TEXT,
  schema_json  JSONB,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tool_name)
);

COMMENT ON TABLE dwp_aura.agent_tool_inventory IS '에이전트 스튜디오: 도구 카탈로그. tool_name은 Aura FINANCE_TOOLS 함수명과 100% 일치.';
COMMENT ON COLUMN dwp_aura.agent_tool_inventory.tool_id IS '도구 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.agent_tool_inventory.tool_name IS '도구명 (Aura 엔진 등록명과 동일)';
COMMENT ON COLUMN dwp_aura.agent_tool_inventory.description IS '도구 설명';
COMMENT ON COLUMN dwp_aura.agent_tool_inventory.schema_json IS '파라미터 규격 (JSON Schema 등)';

-- ----------------------------------------------------------------------
-- 4) agent_tool_mapping (M:N)
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dwp_aura.agent_tool_mapping (
  agent_id   BIGINT NOT NULL REFERENCES dwp_aura.agent_master(agent_id) ON DELETE CASCADE,
  tool_id    BIGINT NOT NULL REFERENCES dwp_aura.agent_tool_inventory(tool_id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (agent_id, tool_id)
);

CREATE INDEX IF NOT EXISTS ix_agent_tool_mapping_tool_id ON dwp_aura.agent_tool_mapping(tool_id);

COMMENT ON TABLE dwp_aura.agent_tool_mapping IS '에이전트–도구 M:N 매핑. tenant는 agent_master.tenant_id로 격리.';

-- ----------------------------------------------------------------------
-- 5) knowledge_base_master
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dwp_aura.knowledge_base_master (
  knowledge_id   BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  name           VARCHAR(255) NOT NULL,
  doc_type       VARCHAR(50) NOT NULL,
  owner_domain   VARCHAR(100),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_knowledge_base_master_tenant_id ON dwp_aura.knowledge_base_master(tenant_id);
CREATE INDEX IF NOT EXISTS ix_knowledge_base_master_owner_domain ON dwp_aura.knowledge_base_master(tenant_id, owner_domain);

COMMENT ON TABLE dwp_aura.knowledge_base_master IS '에이전트 스튜디오: 지식 베이스 마스터.';
COMMENT ON COLUMN dwp_aura.knowledge_base_master.knowledge_id IS '지식베이스 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.knowledge_base_master.tenant_id IS '테넌트 식별자 (격리 필수)';
COMMENT ON COLUMN dwp_aura.knowledge_base_master.name IS '지식베이스명';
COMMENT ON COLUMN dwp_aura.knowledge_base_master.doc_type IS '문서 유형: HIERARCHICAL | SEQUENTIAL';
COMMENT ON COLUMN dwp_aura.knowledge_base_master.owner_domain IS '소유 도메인';
