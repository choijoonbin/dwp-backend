-- V44: Production-Grade Data Seeding for Integrated Workbench (Final)
-- A) fi_doc_item 상세 품목 보강 (DEMO 전표 1:N)
-- B) agent_activity_log 우측 스트림 (케이스별 AI 생각의 흔적)
-- C) case_analysis_run + case_action_proposal 워크벤치 이력 탭
-- D) app_codes DOC_TYPE, RISK_LEVEL, CASE_STATUS (마스터 정규화)
-- Verification: fi_doc_item 10건+, agent_activity_log 10건+

SET search_path TO dwp_aura, public;

-- =============================================================================
-- A. fi_doc_item — 모든 DEMO 전표에 1:N 상세 품목 추가
-- =============================================================================

-- DEMO00001 (중복): 001 기존 유지, 002 추가
INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, sgtxt, created_at)
SELECT 1, '1000', 'DEMO00001', '2025', '002', '600000', 'V001', 500000, 'KRW', '중복 의심 동일 공급업체 라인', CURRENT_TIMESTAMP
FROM dwp_aura.fi_doc_header h
WHERE h.tenant_id = 1 AND h.bukrs = '1000' AND h.belnr = 'DEMO00001' AND h.gjahr = '2025'
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

-- DEMO00002 (식대): 001 sgtxt 갱신, 002·003 추가
UPDATE dwp_aura.fi_doc_item SET sgtxt = '야간 근무 식대(스테이크)', wrbtr = 1200000
WHERE tenant_id = 1 AND bukrs = '1000' AND belnr = 'DEMO00002' AND gjahr = '2025' AND buzei = '001';

INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, sgtxt, created_at)
SELECT 1, '1000', 'DEMO00002', '2025', '002', '600000', 'V001', 300000, 'KRW', '음료(와인)', CURRENT_TIMESTAMP
FROM dwp_aura.fi_doc_header h
WHERE h.tenant_id = 1 AND h.bukrs = '1000' AND h.belnr = 'DEMO00002' AND h.gjahr = '2025'
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, sgtxt, created_at)
SELECT 1, '1000', 'DEMO00002', '2025', '003', '600000', 'V001', 0, 'KRW', '디저트(커피)', CURRENT_TIMESTAMP
FROM dwp_aura.fi_doc_header h
WHERE h.tenant_id = 1 AND h.bukrs = '1000' AND h.belnr = 'DEMO00002' AND h.gjahr = '2025'
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

-- DEMO00003 (지연미결): 002·003 추가
INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, sgtxt, created_at)
SELECT 1, '1000', 'DEMO00003', '2025', '002', '600000', 'V002', 200000, 'KRW', '미결 보류 항목', CURRENT_TIMESTAMP
FROM dwp_aura.fi_doc_header h
WHERE h.tenant_id = 1 AND h.bukrs = '1000' AND h.belnr = 'DEMO00003' AND h.gjahr = '2025'
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, sgtxt, created_at)
SELECT 1, '1000', 'DEMO00003', '2025', '003', '600000', 'V002', 100000, 'KRW', '추가 지연 항목', CURRENT_TIMESTAMP
FROM dwp_aura.fi_doc_header h
WHERE h.tenant_id = 1 AND h.bukrs = '1000' AND h.belnr = 'DEMO00003' AND h.gjahr = '2025'
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

-- NORM00001, NORM00002: 품목 002 추가 (10건+ 확보)
INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, sgtxt, created_at)
SELECT 1, '1000', 'NORM00001', '2025', '002', '600000', 'V001', 5000, 'KRW', '부가 세금', CURRENT_TIMESTAMP
FROM dwp_aura.fi_doc_header h
WHERE h.tenant_id = 1 AND h.bukrs = '1000' AND h.belnr = 'NORM00001' AND h.gjahr = '2025'
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, sgtxt, created_at)
SELECT 1, '1000', 'NORM00002', '2025', '002', '600000', 'V002', 5000, 'KRW', '부가 세금', CURRENT_TIMESTAMP
FROM dwp_aura.fi_doc_header h
WHERE h.tenant_id = 1 AND h.bukrs = '1000' AND h.belnr = 'NORM00002' AND h.gjahr = '2025'
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

-- =============================================================================
-- Agent Case 시드 (DEMO 3건 — 활동/이력 연결용)
-- agent_case는 부분 유니크(tenant_id, dedup_key WHERE dedup_key IS NOT NULL)만 있어
-- ON CONFLICT 대신 INSERT ... WHERE NOT EXISTS 로 중복 방지.
-- =============================================================================
INSERT INTO dwp_aura.agent_case (tenant_id, detected_at, bukrs, belnr, gjahr, buzei, case_type, severity, score, reason_text, status, dedup_key, created_at, updated_at)
SELECT 1, CURRENT_TIMESTAMP - INTERVAL '2 days', '1000', 'DEMO00001', '2025', '001', 'DUPLICATE_INVOICE', 'HIGH', 0.85, '동일 공급업체 중복 전표 의심', 'IN_PROGRESS', '1:WINDOW_DOC_ENTRY:1000-DEMO00001-2025', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM dwp_aura.agent_case c WHERE c.tenant_id = 1 AND c.dedup_key = '1:WINDOW_DOC_ENTRY:1000-DEMO00001-2025');
INSERT INTO dwp_aura.agent_case (tenant_id, detected_at, bukrs, belnr, gjahr, buzei, case_type, severity, score, reason_text, status, dedup_key, created_at, updated_at)
SELECT 1, CURRENT_TIMESTAMP - INTERVAL '1 day',  '1000', 'DEMO00002', '2025', '001', 'THRESHOLD_BREACH', 'MEDIUM', 0.72, '식대 한도 초과 가능성', 'IN_PROGRESS', '1:WINDOW_DOC_ENTRY:1000-DEMO00002-2025', CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM dwp_aura.agent_case c WHERE c.tenant_id = 1 AND c.dedup_key = '1:WINDOW_DOC_ENTRY:1000-DEMO00002-2025');
INSERT INTO dwp_aura.agent_case (tenant_id, detected_at, bukrs, belnr, gjahr, buzei, case_type, severity, score, reason_text, status, dedup_key, created_at, updated_at)
SELECT 1, CURRENT_TIMESTAMP - INTERVAL '5 hours', '1000', 'DEMO00003', '2025', '001', 'DEFAULT', 'HIGH', 0.90, '장기 미결 항목', 'OPEN', '1:WINDOW_DOC_ENTRY:1000-DEMO00003-2025', CURRENT_TIMESTAMP - INTERVAL '5 hours', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM dwp_aura.agent_case c WHERE c.tenant_id = 1 AND c.dedup_key = '1:WINDOW_DOC_ENTRY:1000-DEMO00003-2025');

-- =============================================================================
-- B. agent_activity_log — 케이스별 AI 생각의 흔적 (10건+)
-- resource_type='AGENT_CASE', resource_id=case_id, metadata_json.message
-- =============================================================================
INSERT INTO dwp_aura.agent_activity_log (tenant_id, stage, event_type, resource_type, resource_id, occurred_at, metadata_json, created_at, updated_at)
SELECT c.tenant_id, 'THOUGHT', 'RAG_SEARCH', 'AGENT_CASE', c.case_id::TEXT, c.detected_at + INTERVAL '1 second',
  '{"message":"RAG 지식 베이스 검색 중..."}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM dwp_aura.agent_case c WHERE c.tenant_id = 1 AND c.belnr IN ('DEMO00001','DEMO00002','DEMO00003');

INSERT INTO dwp_aura.agent_activity_log (tenant_id, stage, event_type, resource_type, resource_id, occurred_at, metadata_json, created_at, updated_at)
SELECT c.tenant_id, 'THOUGHT', 'REGULATION_CHECK', 'AGENT_CASE', c.case_id::TEXT, c.detected_at + INTERVAL '2 seconds',
  '{"message":"규정 제11조와 대조 완료"}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM dwp_aura.agent_case c WHERE c.tenant_id = 1 AND c.belnr IN ('DEMO00001','DEMO00002','DEMO00003');

INSERT INTO dwp_aura.agent_activity_log (tenant_id, stage, event_type, resource_type, resource_id, occurred_at, metadata_json, created_at, updated_at)
SELECT c.tenant_id, 'RESULT', 'SCORE_CALC', 'AGENT_CASE', c.case_id::TEXT, c.detected_at + INTERVAL '3 seconds',
  '{"message":"리스크 점수 85점 산출"}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM dwp_aura.agent_case c WHERE c.tenant_id = 1 AND c.belnr IN ('DEMO00001','DEMO00002','DEMO00003');

INSERT INTO dwp_aura.agent_activity_log (tenant_id, stage, event_type, resource_type, resource_id, occurred_at, metadata_json, created_at, updated_at)
SELECT c.tenant_id, 'RESULT', 'ANALYSIS_DONE', 'AGENT_CASE', c.case_id::TEXT, c.detected_at + INTERVAL '4 seconds',
  '{"message":"AI 에이전트 분석 완료"}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM dwp_aura.agent_case c WHERE c.tenant_id = 1 AND c.belnr IN ('DEMO00001','DEMO00002','DEMO00003');

-- =============================================================================
-- C. case_analysis_run + case_analysis_result + case_action_proposal (워크벤치 이력)
-- "시스템에 의해 자동 생성됨" -> "AI 에이전트 분석 완료" 단계 기록
-- =============================================================================
INSERT INTO dwp_aura.case_analysis_run (run_id, tenant_id, case_id, status, mode, requested_by, started_at, finished_at, created_at)
SELECT gen_random_uuid(), c.tenant_id, c.case_id, 'COMPLETED', 'LIVE', 'SYSTEM', c.detected_at, c.detected_at + INTERVAL '10 seconds', CURRENT_TIMESTAMP
FROM dwp_aura.agent_case c WHERE c.tenant_id = 1 AND c.belnr IN ('DEMO00001','DEMO00002','DEMO00003');

INSERT INTO dwp_aura.case_analysis_result (run_id, score, severity, reason_text, evidence_json, created_at)
SELECT r.run_id, c.score, c.severity, c.reason_text, '{"source":"시스템에 의해 자동 생성됨"}'::jsonb, CURRENT_TIMESTAMP
FROM dwp_aura.case_analysis_run r
JOIN dwp_aura.agent_case c ON c.tenant_id = r.tenant_id AND c.case_id = r.case_id
WHERE r.tenant_id = 1 AND r.status = 'COMPLETED';

INSERT INTO dwp_aura.case_action_proposal (tenant_id, case_id, run_id, type, status, risk_level, rationale, dedup_key, created_at, updated_at)
SELECT c.tenant_id, c.case_id, r.run_id, 'FLAG_REVIEW', 'PROPOSED', 'MEDIUM', 'AI 에이전트 분석 완료. 검토 요청.', 'seed-' || c.case_id::text || '-' || r.run_id::text, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM dwp_aura.case_analysis_run r
JOIN dwp_aura.agent_case c ON c.tenant_id = r.tenant_id AND c.case_id = r.case_id
WHERE r.tenant_id = 1 AND r.status = 'COMPLETED'
ON CONFLICT (case_id, run_id, dedup_key) WHERE run_id IS NOT NULL DO NOTHING;

-- =============================================================================
-- D. app_code_groups + app_codes — DOC_TYPE, RISK_LEVEL, CASE_STATUS
-- =============================================================================
INSERT INTO dwp_aura.app_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
  ('DOC_TYPE', '문서 유형', '문서 분류 (규정, 정책, 지침)', true, now(), now()),
  ('RISK_LEVEL', '리스크 수준', '리스크 수준 (HIGH, MEDIUM, LOW, NORMAL)', true, now(), now())
ON CONFLICT (group_key) DO UPDATE SET group_name = EXCLUDED.group_name, description = EXCLUDED.description, updated_at = now();

-- CASE_STATUS 그룹은 V18에 이미 있음. NEW, IGNORED 코드 추가
INSERT INTO dwp_aura.app_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES ('CASE_STATUS', 'Case Status', '케이스 상태', true, now(), now())
ON CONFLICT (group_key) DO UPDATE SET group_name = EXCLUDED.group_name, description = EXCLUDED.description, updated_at = now();

INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('DOC_TYPE', 'REGULATION', '규정', '규정 문서', 10, true, now(), now()),
  ('DOC_TYPE', 'POLICY', '정책', '정책 문서', 20, true, now(), now()),
  ('DOC_TYPE', 'GUIDE', '지침', '지침/가이드 문서', 30, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();

INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('RISK_LEVEL', 'HIGH', 'High', '높음', 10, true, now(), now()),
  ('RISK_LEVEL', 'MEDIUM', 'Medium', '중간', 20, true, now(), now()),
  ('RISK_LEVEL', 'LOW', 'Low', '낮음', 30, true, now(), now()),
  ('RISK_LEVEL', 'NORMAL', 'Normal', '정상', 40, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();

INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('CASE_STATUS', 'NEW', 'New', '신규', 5, true, now(), now()),
  ('CASE_STATUS', 'IN_PROGRESS', 'In Progress', '진행중', 25, true, now(), now()),
  ('CASE_STATUS', 'RESOLVED', 'Resolved', '해결됨', 60, true, now(), now()),
  ('CASE_STATUS', 'IGNORED', 'Ignored', '무시됨', 76, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();

-- =============================================================================
-- Verification (run after migration):
-- SELECT COUNT(*) FROM dwp_aura.fi_doc_item;        -- expect >= 10
-- SELECT COUNT(*) FROM dwp_aura.agent_activity_log; -- expect >= 10
-- Frontend: workbench case detail API returns timeline (agent_activity_log) and items (fi_doc_item).
