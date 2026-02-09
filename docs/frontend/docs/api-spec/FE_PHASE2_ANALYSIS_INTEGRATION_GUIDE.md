# Phase2 케이스 분석 — FE 연동 가이드

Aura/BE Phase2 구현 내용을 바탕으로 FE 연동 방향 및 수정 범위를 정리했습니다.

---

## 1. FE 연동 원칙: **BE를 진입점으로 사용**

| 구분 | 권장 |
|------|------|
| 진입점 | **BE** (`/api/synapse/**`) |
| Aura 직접 호출 | **권장하지 않음** |

- FE는 **BE API만** 호출합니다.
- BE가 트리거 시 Aura를 호출하고, 분석 완료 후 Aura → BE 콜백으로 결과가 저장됩니다.
- FE는 BE에 저장된 결과를 조회합니다.

---

## 2. Phase2 권장 플로우

```
FE → POST /api/synapse/cases/{caseId}/analysis-runs
     → 응답: { runId, status, streamUrl }

FE → streamUrl로 SSE 구독 (진행 상황 표시)

(분석 완료 후)
FE → GET /api/synapse/cases/{caseId}/analysis          (캐시된 분석 결과)
FE → GET /api/synapse/cases/{caseId}/action-proposals  (액션 제안 목록)
```

---

## 3. API 계약 (FE가 사용할 BE API)

| # | Method | 경로 | 설명 |
|---|--------|------|------|
| 1 | POST | `/api/synapse/cases/{caseId}/analysis-runs` | 분석 트리거 (Body 선택) |
| 2 | GET | `/api/synapse/analysis-runs/{runId}` | 분석 실행 상태 |
| 3 | GET | `/api/synapse/analysis-runs/{runId}/stream` | SSE 스트림 (기본) |
| 4 | GET | `/api/synapse/cases/{caseId}/analysis` | 캐시된 분석 결과 |
| 5 | GET | `/api/synapse/cases/{caseId}/action-proposals` | 액션 제안 목록 |
| 6 | POST | `/api/synapse/cases/{caseId}/action-proposals/{proposalId}/approve` | 승인 |
| 7 | POST | `/api/synapse/cases/{caseId}/action-proposals/{proposalId}/reject` | 거절 |

---

## 4. FE 수정 범위 (현재 vs Phase2)

| 항목 | 현재 FE | Phase2 (권장) | 비고 |
|------|---------|---------------|------|
| **분석 트리거** | `POST /api/synapse/agent-tools/agents/finance/stream` (body에 caseId) | `POST /api/synapse/cases/{caseId}/analysis-runs` | caseId를 path에 포함 |
| **트리거 응답** | SSE 직접 수신 | `{ runId, status, streamUrl }` JSON | streamUrl로 별도 SSE 구독 |
| **스트림** | 위 API에서 SSE 직접 수신 | `GET {streamUrl}` (기본: `/api/synapse/analysis-runs/{runId}/stream`) | 2단계 호출 |
| **분석 결과** | `GET /api/synapse/cases/{caseId}/analysis` | 동일 | 변경 없음 |
| **액션 제안** | case detail의 `action.actions` | `GET /api/synapse/cases/{caseId}/action-proposals` | **경로/소스 변경** |
| **승인/거절** | `POST .../actions/{id}/approve`, `.../reject` | `POST .../action-proposals/{proposalId}/approve`, `.../reject` | **리소스명 변경**: actions → action-proposals |

---

## 5. 스트림 (streamUrl) 선택

| 옵션 | 경로 | 이벤트 | 용도 |
|------|------|--------|------|
| **A (기본)** | `GET /api/synapse/analysis-runs/{runId}/stream` | started, completed, failed | 단순 진행 표시 |
| **B (선택)** | `GET /api/aura/cases/{caseId}/analysis/stream?runId={runId}` | started, step, evidence, confidence, proposal, completed, failed | 상세 진행 표시 |

- **기본값**: BE는 `streamUrl`에 (A)를 반환합니다.
- Aura가 (B)를 지원하고, 트리거 응답에 `streamUrl`을 포함하면 BE가 그 값을 FE에 전달합니다.
- Host 프록시: Gateway가 이미 `/api/aura/**`를 Aura로 라우팅하므로, FE가 `{gateway}/api/aura/...`를 호출하면 됩니다. **별도 Host 설정 필요 없음.**

---

## 6. Host / Gateway 프록시

| 질문 | 답변 |
|------|------|
| `/api/aura/**` 호출 시 | Gateway(8080)가 Aura(9000)로 전달 |
| 별도 Host API 프록시 설정 | **불필요** — Gateway에 이미 설정됨 |

FE가 DWP Host를 통해 `{gateway}:8080`으로 요청하면, Gateway가 `/api/synapse/**` → synapsex, `/api/aura/**` → Aura로 라우팅합니다.

---

## 7. DEMO 모드

| 구분 | 동작 |
|------|------|
| BE | `SYNAPSE_DEMO_MODE=true` → 트리거 시 Aura 미호출, 즉시 완료 + 샘플 result/proposal |
| Aura | `DEMO_OFF` → 트리거 시 `{"status":"disabled"}` 반환 |

FE는 BE의 DEMO 모드 기준으로 동작하면 됩니다. BE가 DEMO 모드일 때는 트리거 후 바로 completed + 샘플 데이터가 반환됩니다.

---

## 8. 요약: FE 수정 작업

1. **트리거**: `POST /api/synapse/cases/{caseId}/analysis-runs` 사용, 응답의 `runId`, `streamUrl` 수신
2. **스트림**: `streamUrl`로 SSE 구독 (기본: BE 스트림)
3. **액션 제안**: `GET /api/synapse/cases/{caseId}/action-proposals` 사용
4. **승인/거절**: `action-proposals/{proposalId}/approve`, `reject` 사용
5. **분석 결과**: `GET /api/synapse/cases/{caseId}/analysis` 유지

---

## 9. FE 추가 질문 답변 (5건)

### 9.1 action-proposals vs relatedActions — 관계 및 대체 여부

| 구분 | API/소스 | 테이블 | 식별자 | 용도 |
|------|----------|--------|--------|------|
| **relatedActions** | case detail `action.actions` | agent_action | actionId (Long) | Agent Tool / HITL 기반 액션 (실행/승인/거절 이력) |
| **action-proposals** | `GET /api/synapse/cases/{caseId}/action-proposals` | case_action_proposal | proposalId (UUID) | Phase2 AI 분석 기반 권고 (PAYMENT_BLOCK, REQUEST_INFO 등) |

**결론**
- **대체 관계 아님** — 서로 다른 개념입니다.
- **Phase2 Case Tab "액션 제안" UI** → `action-proposals` API 사용
- **Case 리스트의 relatedActionsCount** → AgentAction 기반 (기존 유지)
- FE는 Phase2 분석 결과의 권고를 보여줄 때 `action-proposals`를 사용하면 됩니다.

---

### 9.2 proposalId vs actionId — 매핑 및 응답 필드

| 구분 | 타입 | API | 용도 |
|------|------|-----|------|
| **proposalId** | UUID | `/action-proposals/{proposalId}/approve`, `reject` | Phase2 AI 권고 식별 |
| **actionId** | Long | `/actions/{actionId}/approve`, `execute`, `reject` | AgentAction(기존) 식별 |

**매핑**
- **직접 매핑 없음** — 별도 엔티티입니다.
- Phase2 승인/거절: `proposalId` 사용
- 기존 Agent Tool 액션: `actionId` 사용

**action-proposals 응답**: `proposalId` 포함, `actionId` 없음.

---

### 9.3 action-proposals 응답 스키마

**GET /api/synapse/cases/{caseId}/action-proposals** 응답: `List<CaseActionProposalDto>`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| proposalId | UUID | ✅ | 액션 제안 식별자 |
| runId | UUID | 선택 | 분석 run 식별자 (nullable) |
| type | String | ✅ | PAYMENT_BLOCK, REQUEST_INFO, HOLD_PAYMENT 등 |
| status | String | ✅ | DRAFT, PROPOSED, APPROVED, REJECTED, EXECUTED, FAILED |
| riskLevel | String | 선택 | MEDIUM, HIGH 등 |
| rationale | String | 선택 | 권고 근거 |
| payload | Object (JSON) | 선택 | 추가 데이터 |
| createdAt | Instant (ISO-8601) | 선택 | 생성 시각 |

---

### 9.4 DEMO 모드 스트림 — 운영과 동일 이벤트 형식 여부

| 항목 | 답변 |
|------|------|
| 이벤트 형식 | **운영과 동일** |
| 이벤트 종류 | `started`, `completed`, `failed` |
| 요청 경로 | `GET /api/synapse/analysis-runs/{runId}/stream` |

DEMO 모드에서도 동일한 스트림 API를 사용하며, `started` → `completed` 순서로 이벤트가 전달됩니다. (DEMO는 완료가 즉시 발생)

---

### 9.5 analysis 결과와 runId — 조회 기준, runId 기반 조회

| 항목 | 답변 |
|------|------|
| **여러 run 존재 시** | `startedAt` 기준 **가장 최근 COMPLETED** run 결과 반환 |
| **runId 파라미터** | `GET /api/synapse/cases/{caseId}/analysis`는 runId 미지원 |
| **runId 기반 조회** | 현재 **미지원** (추가 API 검토 가능) |

**현재 동작**
- `GET /api/synapse/cases/{caseId}/analysis` → 항상 "최신 완료 run" 결과
- 각 proposal에 `runId` 필드가 있으므로, 특정 run의 proposal 구분은 가능

**runId로 run 상태 조회**
- `GET /api/synapse/analysis-runs/{runId}` → run 상태만 조회 가능 (result 미포함)

### 9.5-1 analysis Empty 응답 (결과 없을 때)

| 필드 | 타입 | 설명 |
|------|------|------|
| empty | Boolean | 결과 없을 때 `true` |
| reason | String | FE TabEmptyState 표시용. 예: "아직 분석 결과가 없습니다(Phase2-1: BE demo stream 단계)." |

---

## 10. FE 추가 질문 답변 (2건)

### 10.1 BE 스트림 이벤트 형식 — data 필드 스키마

**GET /api/synapse/analysis-runs/{runId}/stream** SSE 이벤트별 `data` 필드 (JSON):

| event | data 스키마 | 비고 |
|-------|-------------|------|
| **started** | `{ "status": "started", "runId": "<uuid>" }` | |
| **step** | `{ "label": "Normalize evidence", "percent": 20, "detail": "" }` | 2개 전송 (20%, 60%) |
| **completed** | `{ "status": "completed", "runId": "<uuid>" }` | |
| **failed** | `{ "status": "failed", "runId": "<uuid>", "message": "<errorMessage>" }` | message: error_message 또는 빈 문자열 |

**시퀀스**: started → step → step → completed (또는 failed)

---

### 10.2 DEMO_OFF 시 BE 처리 방식

**배경**: Aura가 `DEMO_OFF`일 때 `HTTP 200` + `{"status":"disabled","message":"Analysis disabled (DEMO_OFF)"}` 반환

**BE 처리 (반영 완료)**
- Aura `status=="disabled"` 응답 시 run을 `FAILED`로 변경, `error_message`에 Aura message 저장
- Feign 예외 시에도 run을 `FAILED`로 변경

| 항목 | 동작 |
|------|------|
| run 상태 | `FAILED` |
| error_message | `"Analysis disabled (DEMO_OFF)"` (Aura message) 또는 Feign 예외 시 `"Aura analyze trigger failed: ..."` |
| 트리거 응답 | `status: "FAILED"` 반환 |
| 스트림 | `started` → `failed` (JSON: `{ status, runId, message }`) |

**FE 권장**
- 트리거 응답 `status === "FAILED"`면 스트림 구독 없이 `message` 표시
- 스트림 `failed` 이벤트 수신 시 `data.message` 사용. 예: `"분석 서비스를 사용할 수 없습니다. (데모 모드)"`

---

*작성일: 2025-02-09*
