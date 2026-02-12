-- Aura 도구 정보(이름·설명·파라미터) 반영: agent_tool_inventory description + schema_json
-- 기준: aura.txt 첨부 "agent_tool_inventory 시드용 도구 정보" (Aura → 백엔드)
-- Aura 호출 시 GET /api/v1/agents/{id}/config 등에서 tools[].description, tools[].schemaJson 으로 전달됨

SET search_path TO dwp_aura, public;

-- 1. get_case
UPDATE dwp_aura.agent_tool_inventory
SET description = '케이스 상세 정보를 조회합니다. Synapse 백엔드 Tool API를 통해 중복송장 의심 케이스 등의 상세를 가져옵니다.',
    schema_json = '{"type":"object","properties":{"caseId":{"type":"string","description":"케이스 ID"}},"required":["caseId"]}'::jsonb,
    updated_at = now()
WHERE tool_name = 'get_case';

-- 2. search_documents
UPDATE dwp_aura.agent_tool_inventory
SET description = '문서를 검색합니다. Synapse GET /documents (query: bukrs, gjahr, page, size 등). caseId만 있으면 get_case로 case 조회 후 bukrs/gjahr 추출하여 documents 호출.',
    schema_json = '{"type":"object","properties":{"filters":{"type":"object","description":"검색 필터 (caseId, bukrs, gjahr, page, size 등)"}},"required":[]}'::jsonb,
    updated_at = now()
WHERE tool_name = 'search_documents';

-- 3. get_document
UPDATE dwp_aura.agent_tool_inventory
SET description = '단일 문서를 조회합니다.',
    schema_json = '{"type":"object","properties":{"bukrs":{"type":"string","description":"회사 코드"},"belnr":{"type":"string","description":"전표 번호"},"gjahr":{"type":"string","description":"회계 연도"}},"required":["bukrs","belnr","gjahr"]}'::jsonb,
    updated_at = now()
WHERE tool_name = 'get_document';

-- 4. get_lineage
UPDATE dwp_aura.agent_tool_inventory
SET description = '전표/문서의 라인리지(Lineage)를 조회합니다. caseId 우선, 없으면 belnr+gjahr(+bukrs) 사용.',
    schema_json = '{"type":"object","properties":{"caseId":{"type":"string","description":"케이스 ID (문서 조회용)"},"belnr":{"type":"string","description":"전표 번호"},"gjahr":{"type":"string","description":"회계 연도"},"bukrs":{"type":"string","description":"회사 코드"}},"required":[]}'::jsonb,
    updated_at = now()
WHERE tool_name = 'get_lineage';

-- 5. get_entity
UPDATE dwp_aura.agent_tool_inventory
SET description = '엔티티 정보를 조회합니다.',
    schema_json = '{"type":"object","properties":{"entityId":{"type":"string","description":"엔티티 ID"}},"required":["entityId"]}'::jsonb,
    updated_at = now()
WHERE tool_name = 'get_entity';

-- 6. get_open_items
UPDATE dwp_aura.agent_tool_inventory
SET description = '미결 항목(Open Items)을 조회합니다. Synapse GET /open-items (query: type, overdueBucket, page, size).',
    schema_json = '{"type":"object","properties":{"filters":{"type":"object","description":"필터 (type: AR|AP, overdueBucket, page, size 등)"}},"required":[]}'::jsonb,
    updated_at = now()
WHERE tool_name = 'get_open_items';

-- 7. web_search
UPDATE dwp_aura.agent_tool_inventory
SET description = '외부 지능형 웹 검색을 실행합니다. 회계/세무 기준, 국세청 가이드라인, 업종별 지출 관행 등을 검색할 때 사용합니다. 검색 결과에는 원문 URL이 포함되며, 답변 작성 시 [설명](URL) 마크다운 형식으로 인용합니다.',
    schema_json = '{"type":"object","properties":{"query":{"type":"string","description":"검색 쿼리 (예: 국세청 법인카드 세무처리, 회계기준 경비 인정)"}},"required":["query"]}'::jsonb,
    updated_at = now()
WHERE tool_name = 'web_search';

-- 8. simulate_action
UPDATE dwp_aura.agent_tool_inventory
SET description = '액션을 시뮬레이션합니다. 실제 실행 없이 결과를 미리 확인합니다. X-Idempotency-Key로 중복 호출 방지.',
    schema_json = '{"type":"object","properties":{"caseId":{"type":"string","description":"케이스 ID"},"actionType":{"type":"string","description":"액션 타입 (예: write_off, clear)"},"payload":{"type":"object","description":"액션 파라미터"},"idempotency_key":{"type":"string","description":"멱등성 키 (중복 호출 방지, 미지정 시 자동 생성)"}},"required":["caseId","actionType"]}'::jsonb,
    updated_at = now()
WHERE tool_name = 'simulate_action';

-- 9. propose_action
UPDATE dwp_aura.agent_tool_inventory
SET description = '액션을 제안합니다. 위험도가 높거나 Guardrail에 걸리면 HITL 승인이 필요합니다. 승인 필요 시 에이전트가 interrupt되고, 사용자 승인 후 execute_action으로 실행됩니다.',
    schema_json = '{"type":"object","properties":{"caseId":{"type":"string","description":"케이스 ID"},"actionType":{"type":"string","description":"액션 타입"},"payload":{"type":"object","description":"액션 파라미터"}},"required":["caseId","actionType"]}'::jsonb,
    updated_at = now()
WHERE tool_name = 'propose_action';

-- 10. execute_action
UPDATE dwp_aura.agent_tool_inventory
SET description = '승인 완료된 액션을 실행합니다. HITL 승인 후 Synapse 백엔드에서 전달한 actionId로 호출합니다. X-Idempotency-Key로 중복 실행 방지.',
    schema_json = '{"type":"object","properties":{"actionId":{"type":"string","description":"승인 완료된 액션 ID"},"idempotency_key":{"type":"string","description":"멱등성 키 (중복 실행 방지, 미지정 시 actionId 사용)"}},"required":["actionId"]}'::jsonb,
    updated_at = now()
WHERE tool_name = 'execute_action';
