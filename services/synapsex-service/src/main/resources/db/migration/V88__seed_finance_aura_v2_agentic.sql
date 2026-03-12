-- finance_aura_v2_agentic 정식 등록 및 초기 바인딩
SET search_path TO dwp_aura, public;

-- 1) 에이전트 마스터 upsert (tenant=1)
INSERT INTO dwp_aura.agent_master (
    tenant_id, agent_key, name, domain, model_name, temperature, max_tokens, is_active, created_at, updated_at
)
VALUES (
    1,
    'finance_aura_v2_agentic',
    'Finance 감사 에이전트 V2 (Agentic)',
    'FINANCE',
    COALESCE((SELECT m.model_name FROM dwp_aura.agent_master m WHERE m.tenant_id = 1 AND m.agent_key = 'finance_aura' LIMIT 1), 'gpt-4o'),
    COALESCE((SELECT m.temperature FROM dwp_aura.agent_master m WHERE m.tenant_id = 1 AND m.agent_key = 'finance_aura' LIMIT 1), 0.20),
    COALESCE((SELECT m.max_tokens FROM dwp_aura.agent_master m WHERE m.tenant_id = 1 AND m.agent_key = 'finance_aura' LIMIT 1), 4096),
    true,
    now(),
    now()
)
ON CONFLICT (tenant_id, agent_key)
DO UPDATE SET
    name = EXCLUDED.name,
    domain = EXCLUDED.domain,
    is_active = true,
    updated_at = now(),
    model_name = COALESCE(dwp_aura.agent_master.model_name, EXCLUDED.model_name),
    temperature = COALESCE(dwp_aura.agent_master.temperature, EXCLUDED.temperature),
    max_tokens = COALESCE(dwp_aura.agent_master.max_tokens, EXCLUDED.max_tokens);

-- 2) 현재 프롬프트 없으면 finance_aura 현재 프롬프트 복제
INSERT INTO dwp_aura.agent_prompt_history (agent_id, system_instruction, version, is_current, created_at)
SELECT
    v2.agent_id,
    COALESCE(
        (
            SELECT f.system_instruction
            FROM dwp_aura.agent_prompt_history f
            JOIN dwp_aura.agent_master fm ON fm.agent_id = f.agent_id
            WHERE fm.tenant_id = 1
              AND fm.agent_key = 'finance_aura'
              AND f.is_current = true
            ORDER BY f.version DESC
            LIMIT 1
        ),
        'You are Aura finance agent v2.'
    ) AS system_instruction,
    COALESCE((SELECT MAX(h.version) + 1 FROM dwp_aura.agent_prompt_history h WHERE h.agent_id = v2.agent_id), 1) AS version,
    true,
    now()
FROM dwp_aura.agent_master v2
WHERE v2.tenant_id = 1
  AND v2.agent_key = 'finance_aura_v2_agentic'
  AND NOT EXISTS (
      SELECT 1
      FROM dwp_aura.agent_prompt_history cur
      WHERE cur.agent_id = v2.agent_id
        AND cur.is_current = true
  );

-- 3) tool inventory 보장 (핵심 10개)
INSERT INTO dwp_aura.agent_tool_inventory (tool_name, description, updated_at)
VALUES
  ('get_case', '케이스 상세 조회', now()),
  ('search_documents', '문서 및 사내 규정(RAG) 검색', now()),
  ('get_document', '단일 문서 상세 조회', now()),
  ('get_entity', '거래처/엔티티 정보 조회', now()),
  ('get_open_items', '미결 항목 조회', now()),
  ('get_lineage', '라인리지 조회', now()),
  ('web_search', '외부 지능형 웹 검색 (Tavily 등)', now()),
  ('simulate_action', '액션 실행 결과 미리보기', now()),
  ('propose_action', '조치 제안 (HITL 승인 프로세스)', now()),
  ('execute_action', '조치 실행', now())
ON CONFLICT (tool_name)
DO UPDATE SET description = EXCLUDED.description, updated_at = now();

-- 4) v2 agent ↔ tool 매핑
INSERT INTO dwp_aura.agent_tool_mapping (agent_id, tool_id, created_at)
SELECT m.agent_id, i.tool_id, now()
FROM dwp_aura.agent_master m
JOIN dwp_aura.agent_tool_inventory i ON i.tool_name IN (
  'get_case', 'search_documents', 'get_document', 'get_entity', 'get_open_items',
  'get_lineage', 'web_search', 'simulate_action', 'propose_action', 'execute_action'
)
WHERE m.tenant_id = 1
  AND m.agent_key = 'finance_aura_v2_agentic'
  AND NOT EXISTS (
      SELECT 1
      FROM dwp_aura.agent_tool_mapping mt
      WHERE mt.agent_id = m.agent_id
        AND mt.tool_id = i.tool_id
  );

-- 5) v2 agent ↔ 문서 매핑 (23,26 우선)
INSERT INTO dwp_aura.agent_document_mapping (agent_id, doc_id, tenant_id, created_at, created_by, updated_at, updated_by)
SELECT m.agent_id, d.doc_id, 1, now(), 1, now(), 1
FROM dwp_aura.agent_master m
JOIN dwp_aura.rag_document d
  ON d.tenant_id = 1
 AND d.doc_id IN (23, 26)
WHERE m.tenant_id = 1
  AND m.agent_key = 'finance_aura_v2_agentic'
  AND NOT EXISTS (
      SELECT 1
      FROM dwp_aura.agent_document_mapping adm
      WHERE adm.agent_id = m.agent_id
        AND adm.doc_id = d.doc_id
  );

-- 6) 문서 매핑이 비어 있으면 tenant=1의 최신 문서 2건 fallback 바인딩
INSERT INTO dwp_aura.agent_document_mapping (agent_id, doc_id, tenant_id, created_at, created_by, updated_at, updated_by)
SELECT x.agent_id, x.doc_id, 1, now(), 1, now(), 1
FROM (
    SELECT m.agent_id, d.doc_id,
           ROW_NUMBER() OVER (ORDER BY d.created_at DESC, d.doc_id DESC) AS rn
    FROM dwp_aura.agent_master m
    JOIN dwp_aura.rag_document d ON d.tenant_id = 1
    WHERE m.tenant_id = 1
      AND m.agent_key = 'finance_aura_v2_agentic'
      AND NOT EXISTS (
          SELECT 1
          FROM dwp_aura.agent_document_mapping adm0
          WHERE adm0.agent_id = m.agent_id
      )
) x
WHERE x.rn <= 2
  AND NOT EXISTS (
      SELECT 1
      FROM dwp_aura.agent_document_mapping adm
      WHERE adm.agent_id = x.agent_id
        AND adm.doc_id = x.doc_id
  );
