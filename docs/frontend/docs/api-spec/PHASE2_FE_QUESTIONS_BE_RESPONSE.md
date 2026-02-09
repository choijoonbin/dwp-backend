# Phase2 — 백엔드(Synapse) 확인 답변

> FE `front.txt` 질문에 대한 BE 팀 답변

---

## 1. 백엔드 (Synapse) 서버

### 1.1 action-proposals runId 없을 때 동작

**질문**: `GET /api/synapse/cases/{caseId}/action-proposals` (runId 쿼리 없음) 호출 시 응답은?

**답변**: [x] **모든 run의 proposals 반환 (누적)**

- `runId` 없으면: `findByTenantIdAndCaseIdOrderByCreatedAtDesc` → 해당 케이스의 모든 run에 속한 proposals
- `runId` 있으면: 해당 run의 proposals만

**FE 영향**: runId 없을 때 전체 이력(누적) 표시. 최신 run만 보려면 `GET .../analysis-runs?latest=true`로 runId 조회 후 `?runId=...` 붙여 호출.

---

### 1.2 analysis-runs latest=true, run이 없을 때

**질문**: 케이스에 analysis run이 하나도 없을 때 `GET .../analysis-runs?latest=true` 응답은?

**답변**: [x] **`{ data: { runId: null } }`**

```json
{
  "status": "SUCCESS",
  "data": { "runId": null }
}
```

- 404 아님. 200 + `data.runId: null`
- FE: `analysisRunsData?.runId`가 null이면 latestRunId = null로 처리

---

### 1.3 analysis-runs 응답 스키마 (ApiResponse 래핑)

**질문**: `latest=true` 응답이 `ApiResponse` 래핑인지 확인.

**답변**: [x] **맞음**

- `latest=true`: `{ status: "SUCCESS", data: { runId: "uuid-..." } }`
- `latest` 없음(목록): `{ status: "SUCCESS", data: [ { runId, status, startedAt }, ... ] }` (배열)

**FE 영향**: `res.data.runId`(latest) 또는 `res.data`(배열)로 추출.

---

### 1.4 evidenceSnapshot 요청 body 스키마

**질문**: `POST analysis-runs` body의 `evidenceSnapshot` 요구 구조는?

**답변**: [x] **BE 수용 완료**

- BE `AnalysisRunTriggerRequest`: `evidenceSnapshot` (JsonNode) 필드 추가됨
- FE가 보내면 그대로 Aura에 전달. 없으면 `agent_case.evidence_json` + `rag_refs_json`을 합쳐 전달
- FE: `useCaseDetail().evidence` 구조 그대로 전달 가능

---

## 2. Aura 서버 (BE 관점 보완)

### 2.1 status 필드 (STARTED vs ACCEPTED)

**BE 답변**: BE는 `data.status = "STARTED"` 반환 (run 상태). Aura는 `ACCEPTED` 사용. **문서 정합성 이슈** — FE는 `runId`, `streamUrl`만 사용하므로 기능 영향 없음. 필요 시 Aura와 정리 권장.

---

### 2.2 streamUrl 경로 형식

**BE 답변**: [x] **FE는 `/api/synapse/analysis-runs/{runId}/stream` 사용**

- BE가 FE에 돌려주는 `streamUrl`: `/api/synapse/analysis-runs/{runId}/stream`
- Gateway가 `Path=/api/synapse/analysis-runs/**` → synapsex로 라우팅
- FE: `{NX_API_URL}/api/synapse/analysis-runs/{runId}/stream` 사용 (Aura `/aura/...` 직접 호출 아님)

---

### 2.3 step 이벤트 지원 여부

**BE 답변**: [x] **BE 스트림에서 step 이벤트 지원함**

- BE SSE: `started` → `step`(2회: label, percent, detail) → `completed`/`failed`
- `event: step`, `data: { label, detail, percent }` 형식
- DEMO 모드 또는 Aura 미연동 시 BE 스트림 사용

---

## 3. Gateway / Proxy

### 3.1 streamUrl 라우팅

**답변**:

| FE 요청 URL | 라우팅 대상 | 비고 |
|-------------|-------------|------|
| `{NX_API_URL}/api/synapse/analysis-runs/{runId}/stream` | synapsex (8085) | ✅ 사용. POST/analysis와 동일 프록시 |
| `{NX_API_URL}/aura/analysis-runs/{runId}/stream` | Aura Platform (9000) | `/api/aura/**` → Aura. FE는 BE 경로 사용 권장 |

- **권장**: `{NX_API_URL}/api/synapse/analysis-runs/{runId}/stream` 사용
- CORS: Gateway에서 처리. 브라우저 fetch 동일 규칙 적용.

---

## 4. 정리 (답변 후 체크)

| # | 항목 | 담당 | 상태 |
|---|------|------|------|
| 1.1 | action-proposals runId 없을 때 | BE | ✅ |
| 1.2 | analysis-runs run 없을 때 | BE | ✅ |
| 1.3 | analysis-runs ApiResponse 스키마 | BE | ✅ |
| 1.4 | evidenceSnapshot 스키마 | BE | ✅ |
| 2.1 | status STARTED vs ACCEPTED | Aura | ⬜ |
| 2.2 | streamUrl 경로 형식 | BE | ✅ |
| 2.3 | step 이벤트 지원 | BE | ✅ |
| 3.1 | streamUrl 라우팅 | Gateway | ✅ |

---

*작성: BE 팀 | 참조: front.txt, AURA_PHASE2_TRIGGER_ALIGNMENT.md*
