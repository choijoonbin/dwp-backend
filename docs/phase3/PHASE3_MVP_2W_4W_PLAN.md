# Phase3 2주·4주 플랜 (MVP + 고도화)

> **MVP 목표**: "RAG 근거 + LLM 제안 + 승인/거절/실행(시뮬)" E2E 1케이스 완주  
> **재현성**: runId 기반 리플레이, 감사로그 연동

---

## 목표 (2주)

1. Evidence 패키징 표준화 (BE→Aura)
2. RAG 최소 구현 (Aura)
3. 하이브리드 제안 (룰 후보 + LLM 설명/우선순위)
4. 승인/거절/실행(시뮬레이션) 워크플로우 완주
5. 감사로그/재현성(runId) 기반 리플레이

---

## Week 1 — MVP 파이프라인 구축

### A. BE 체크리스트 (Week1)

| 항목 | 상태 | 비고 |
|------|------|------|
| **입력 패키지 표준화** | 🔲 | POST analysis-runs 트리거 시 document/header/items/openItems/party/lineage/policies 포함 |
| | | "document 없음" 케이스: open-item 기반 증적 동일 구조 매핑 |
| **runId / streamUrl 계약** | ✅ | 202 + runId + streamUrl(Aura URL) 반환, FE 200/202 호환 |
| **콜백 수신/저장** | ✅ | POST /api/synapse/internal/aura/callback 구현·연동 |
| | ✅ | case_analysis_result, case_action_proposal 저장 |
| | ✅ | case_analysis_run status COMPLETED/FAILED, error_message |
| **중복 방지** | ✅ | dedup_key(fingerprint) + UNIQUE(case_id, run_id, dedup_key) |

### B. Aura 체크리스트 (Week1)

- RAG 최소 인덱싱 (documents/openItems chunking, topK=3~5)
- 하이브리드 제안 (룰 후보 + LLM rationale/riskLevel)
- Streaming: started / step / agent / completed / failed
- Callback 발송 (완료 시 BE로 POST)

### C. FE 체크리스트 (Week1)

- 분석 시작 → POST analysis-runs, runId/streamUrl 확보
- streamUrl SSE 연결, started/step/agent/completed 반영
- completed 후 GET analysis?runId=..., GET action-proposals?runId=...

---

## Week 2 — MVP E2E 완주 + 최소 운영성

### A. BE 체크리스트 (Week2)

| 항목 | 상태 | 비고 |
|------|------|------|
| GET /cases/{id}/analysis?runId=... 스키마 고정 | ✅ | score/severity/reasonText/evidence/ragRefs/proposals |
| GET /cases/{id}/action-proposals?runId=... | ✅ | runId 필터 지원 |
| POST decision (APPROVE/REJECT) | ✅ | approve, reject 엔드포인트 존재 |
| POST actions/execute (시뮬) + 결과 기록 | 🔲 | 실행 어댑터·결과 저장·감사 이벤트 |
| 감사로그 | ✅ | RUN_STARTED/RUN_COMPLETED/PROPOSAL_CREATED 등 |

### B. Aura 체크리스트 (Week2)

- RAG refs 품질 (excerpt/score/source)
- LLM 출력 스키마 강제
- 실패 시 callback FAILED 반환

### C. FE 체크리스트 (Week2)

- 액션제안 run별 grouping, 최신 run 강조
- fingerprint 중복 1건 표시
- 승인/거절/실행 클릭 → 상태·감사 반영

---

## Week 3–4 (4주: 안정화·고도화)

- **Week 3**: latest=true, run 비교, proposal versioning, 재시도 UX
- **Week 4**: 권한 분리, 실행 어댑터 확장, traceId, Agentic orchestration, run 디버그 패널

---

## 테스트 시나리오 (E2E Gate)

### 시나리오 1: 정상 E2E

- POST analysis-runs → 202 + runId + streamUrl
- SSE: started → step → completed
- GET analysis, GET action-proposals → refs 2개 이상, proposal rationale+fingerprint
- 제안 1개 APPROVE → 실행(시뮬) → 감사로그 run/decision/execute

### 시나리오 2: 재시도 중복 방지

- 동일 케이스 분석 3회 → latest=true, 동일 fingerprint 1건(또는 version)

### 시나리오 3: 실패 케이스

- Aura 오류 → stream failed, case_analysis_run FAILED, error_message

---

## API 스키마 요약

| API | 비고 |
|-----|------|
| POST /api/synapse/cases/{caseId}/analysis-runs | 202 + runId + streamUrl(Aura) |
| GET streamUrl (Aura) | started / step / agent / completed / failed |
| GET /api/synapse/cases/{caseId}/analysis?runId= | runId 기준, evidence/ragRefs 포함 |
| GET /api/synapse/cases/{caseId}/action-proposals?runId= | fingerprint는 BE 계산·반환 |
| POST .../action-proposals/{id}/approve, reject | 결정 |
| POST .../actions/execute (시뮬) | 결과 기록 |

---

## 시스템 정책 (합의)

- BE 응답 202 고정 권장, FE는 200/202 호환
- streamUrl은 Aura URL (BE stream proxy 옵션화 가능)
- proposal 중복 기준: BE fingerprint 단일 진실
- run 조회: runId 기준 + latest=true 옵션
- callback: idempotent upsert
- UNIQUE: MVP는 **UNIQUE(run_id, fingerprint)** (또는 현재 BE: case_id, run_id, dedup_key)

---

## 재시도·중복 정책

- **동일 runId**: 항상 동일 결과(재현성). run 단위 prompt/policy/version 저장 시 재현 가능.
- **다른 runId**: RAG/정책/LLM/외부 데이터 변경으로 결과가 달라질 수 있음.
- **중복 방지**: fingerprint + 최신 run 강조. 같은 제안 merge, 달라진 제안만 신규 노출.
- **UX**: "변경 사유" 표시(근거 추가, 정책 버전, 데이터 갱신) — Week3~4.

## BE 남은 작업 요약 (Week1·2 기준)

| 우선순위 | 항목 | 설명 |
|----------|------|------|
| Week1 | 입력 패키지 표준화 | 트리거 시 document/header/items/openItems/party/lineage/policies 포함, document 없을 때 open-item 매핑 |
| Week2 | 실행(시뮬) + 결과 기록 | POST execute, 실행 결과 저장, ACTION_EXECUTED 감사 |
| 선택 | errorJson | 현재 error_message TEXT → JSONB 확장(필요 시) |
| 선택 | case_action_execution 테이블 | 실행 이력 전용 테이블(4주 확장) |

## 참조

- `docs/api-spec/PHASE3_DDL_VS_CURRENT_SCHEMA.md` — DDL 비교
- `docs/frontend/docs/api-spec/AURA_CALLBACK_500_VERIFICATION_result.md` — 콜백·스트림 검증
- `docs/frontend/docs/api-spec/ANALYSIS_STREAM_END_CONTRACT.md` — 스트림 종료 계약
