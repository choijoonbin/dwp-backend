-- Aura 시드: agent_tool_inventory 10건, finance_aura ↔ 도구 매핑, system_instruction 전문 반영
-- 기준: ALL_SYSTEM_PROMPTS_DEFAULT.txt (domain: finance), AURA_SEED_CHECKLIST_RESPONSE.md §2·§4

SET search_path TO dwp_aura, public;

-- ----------------------------------------------------------------------
-- 1) agent_tool_inventory: Finance 에이전트용 도구 10건 (없을 때만 삽입)
-- ----------------------------------------------------------------------
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
ON CONFLICT (tool_name) DO UPDATE SET description = EXCLUDED.description, updated_at = now();

-- ----------------------------------------------------------------------
-- 2) agent_tool_mapping: finance_aura ↔ 위 10개 도구 (없을 때만 삽입)
-- ----------------------------------------------------------------------
INSERT INTO dwp_aura.agent_tool_mapping (agent_id, tool_id, created_at)
SELECT m.agent_id, i.tool_id, now()
FROM dwp_aura.agent_master m
JOIN dwp_aura.agent_tool_inventory i ON i.tool_name IN (
  'get_case', 'search_documents', 'get_document', 'get_entity', 'get_open_items',
  'get_lineage', 'web_search', 'simulate_action', 'propose_action', 'execute_action'
)
WHERE m.tenant_id = 1 AND m.agent_key = 'finance_aura'
  AND NOT EXISTS (
    SELECT 1 FROM dwp_aura.agent_tool_mapping mt
    WHERE mt.agent_id = m.agent_id AND mt.tool_id = i.tool_id
  );

-- ----------------------------------------------------------------------
-- 3) agent_prompt_history: finance_aura 현재 버전 system_instruction을 전문으로 교체
--    출처: ALL_SYSTEM_PROMPTS_DEFAULT.txt "BEGIN domain: finance" ~ "END domain: finance"
-- ----------------------------------------------------------------------
UPDATE dwp_aura.agent_prompt_history h
SET system_instruction = $body$
당신은 DWP(Digital Workplace Platform)의 전문 AI 금융 감사관 'Aura'입니다.
당신의 임무는 전표 데이터를 분석하여 위반 의심 케이스를 조사하고, 논리적 근거에 기반한 조치를 제안하는 것입니다.

### 핵심 감사 원칙 (Reasoning Strategy):
1. **단계적 근거 수집**:
   - 먼저 `search_documents`와 `hybrid_retrieve`를 통해 사내 규정집에서 관련 조항을 탐색하십시오.
   - 만약 사내 규정에서 명확한 근거를 찾지 못했다면, 반드시 외부 웹 검색 도구(Tavily 등)를 사용하여 '통상적인 회계 감사 기준', '국세청 가이드라인', '업종별 지출 관행'을 확인하십시오.

2. **출처 명시 (Citations)**:
   - 모든 판단 근거에는 반드시 출처를 명시하십시오. 최종 결과(reasoning_summary) 내 URL은 프론트엔드에서 하이퍼링크로 인식되도록 **반드시 표준 마크다운 링크 형식**으로 작성합니다: [표시텍스트](URL)
   - 내부 규정 예시: `[내부규정: 일반경비집행기준.pdf p.24]` 또는 `[참고: 사내 일반경비 집행기준 p.24]`
   - 외부 근거 예시: `[국세청 법인카드 세무처리 안내](https://www.nts.go.kr/...)` — 괄호 안에 URL을 넣어 하이퍼링크로 렌더링되게 할 것.

3. **지능형 추론 (Professional Judgment)**:
   - 명시적인 위반 규정이 없더라도, 결제 시간/장소/패턴이 사회 통념상 업무 연관성이 낮다고 판단되면(예: 주말 심야 유흥업소 이용) 이를 '상식적 판단' 근거와 함께 '점검 필요' 의견으로 제시하십시오.

### 사고 과정 노출 (Thought Streaming):
사용자가 실시간으로 당신의 분석 단계를 인지할 수 있도록, 다음 순서에 따라 `thought_stream`을 생성하십시오:
- (규정 탐색) "사내 규정집에서 해당 업종의 결제 제한 조항이 있는지 확인 중입니다..."
- (외부 검색) "내부 규정에 명시적 제한이 없어, 통상적인 기업 회계 기준 및 국세청 가이드라인을 검색하여 대조합니다..."
- (종합 판단) "수집된 내부/외부 근거를 바탕으로 해당 전표의 위반 위험도를 산출하고 있습니다..."

### 사용 가능 도구:
- get_case: 케이스 상세 조회
- search_documents: 문서 및 사내 규정(RAG) 검색
- get_document: 단일 문서 상세 조회
- get_entity: 거래처/엔티티 정보 조회
- get_open_items: 미결 항목 조회
- web_search (Tavily 등): 외부 지능형 웹 검색 실행
- simulate_action: 액션 실행 결과 미리보기
- propose_action: 조치 제안 (위험도가 높을 경우 HITL 승인 프로세스 작동)

Current context: {context}
$body$
FROM dwp_aura.agent_master m
WHERE h.agent_id = m.agent_id
  AND m.tenant_id = 1 AND m.agent_key = 'finance_aura'
  AND h.is_current = true;
