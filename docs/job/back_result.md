# Phase2 Backend (back.txt) 구현 결과

## 1) DB 스키마 (V32)

- `case_analysis_run`: run_id(UUID), tenant_id, case_id, status, mode, requested_by, started_at, finished_at, error_message, aura_trace_id
- `case_analysis_result`: run_id(PK/FK), score, severity, reason_text, confidence_json, evidence_json, similar_json, rag_refs_json
- `case_action_proposal`: proposal_id(UUID), tenant_id, case_id, run_id, type, status, risk_level, rationale, payload_json

## 2) API 계약 (구현)

| API | Method | 경로 | 설명 |
|-----|--------|------|------|
| (1) | POST | /api/synapse/cases/{caseId}/analysis-runs | 분석 트리거 |
| (2) | GET | /api/synapse/analysis-runs/{runId} | 분석 실행 상태 |
| (3) | GET | /api/synapse/analysis-runs/{runId}/stream | SSE 스트림 |
| (4) | GET | /api/synapse/cases/{caseId}/analysis | 캐시된 분석 결과 (기존 엔드포인트 통합) |
| (5) | GET | /api/synapse/cases/{caseId}/action-proposals | 액션 제안 목록 |
| (6) | POST | /api/synapse/cases/{caseId}/action-proposals/{proposalId}/approve | 승인 |
| (7) | POST | /api/synapse/cases/{caseId}/action-proposals/{proposalId}/reject | 거절 |
| (8) | POST | /api/synapse/internal/aura/callback | Aura 콜백 (내부) |

## 3) Aura 연동

- BE → Aura: `POST /aura/cases/{caseId}/analyze` (Feign AuraCaseTabClient.triggerAnalyze)
- Aura → BE: `POST /api/synapse/internal/aura/callback`

## 4) 감사 로그

- ANALYSIS_RUN_STARTED, ANALYSIS_RUN_COMPLETED, ANALYSIS_RUN_FAILED
- ACTION_PROPOSED, ACTION_APPROVED, ACTION_REJECTED (ACTION_EXECUTED는 Phase3)

## 5) DEMO 모드

- SYNAPSE_DEMO_MODE=true 시: trigger 시 즉시 완료 + 샘플 result/proposal 생성

## 6) Gateway 라우팅

- /api/synapse/analysis-runs/** → synapsex
- /api/synapse/internal/** → synapsex

## 체크리스트

- [x] Postman: trigger → stream (started/completed) → analysis 조회 non-empty
- [x] caseId 하나로 proposals 최소 1개 (DEMO 모드)
- [x] audit_event_log 3종 이상 (STARTED/COMPLETED/PROPOSED)
- [x] DEMO_OFF 시 null-safe fallback
