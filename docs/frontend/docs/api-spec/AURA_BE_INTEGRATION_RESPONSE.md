# Aura ↔ Backend 연동 확인 응답

Aura 팀 확인 요청에 대한 백엔드 현황 및 답변입니다.

---

## 1. 경로 정합성 (BE → Aura 호출)

| 구분 | 현재 백엔드 | Aura 구현 | 결론 |
|------|-------------|-----------|------|
| BE → Aura 트리거 | `POST /aura/cases/{caseId}/analysis-runs` | Aura도 `/analysis-runs` 권장 (back.txt) |

**백엔드 반영 완료**: `AuraCaseTabClient.triggerAnalyze()` → `@PostMapping("/aura/cases/{caseId}/analysis-runs")`

---

## 2. Gateway → Aura 라우팅

| 항목 | 값 |
|------|-----|
| Gateway 경로 | `/api/aura/**` |
| StripPrefix | 1 |
| Aura 수신 경로 | `/aura/**` |
| Aura URL | `${AURA_PLATFORM_URL:http://localhost:9000}` |

**예시**
- 클라이언트: `POST http://localhost:8080/api/aura/cases/85114/analysis-runs`
- Gateway가 Aura(9000)로 전달: `POST http://localhost:9000/aura/cases/85114/analysis-runs`

---

## 3. Aura → BE 콜백 스키마

### 3.1 호출 경로

```
POST /api/synapse/internal/aura/callback
```

- Gateway: `/api/synapse/internal/**` → synapsex-service (StripPrefix=1)
- synapsex 수신: `POST /synapse/internal/aura/callback`

### 3.2 Request Body 스키마 (AuraCallbackPayload)

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| runId | UUID | **필수** | 없으면 콜백 무시. run lookup에 사용 |
| status | String | **필수** | `COMPLETED` \| `FAILED` |
| auraTraceId | String | 선택 | |
| partialEvents | List\<Map\> | 선택 | |
| finalResult | Object | 선택 | status=COMPLETED일 때만 처리 |

**finalResult**

| 필드 | 타입 | 설명 |
|------|------|------|
| score | Double | |
| severity | String | |
| reasonText | String | |
| confidence | Object (JSON) | |
| evidence | List\<Map\> | |
| ragRefs | List\<Map\> | |
| similar | List\<Map\> | |
| proposals | List\<ProposalItem\> | |

**ProposalItem**

| 필드 | 타입 | 설명 |
|------|------|------|
| type | String | 예: PAYMENT_BLOCK, REQUEST_INFO |
| riskLevel | String | 예: MEDIUM |
| rationale | String | |
| payload | Object (JSON) | JsonNode |

**참고**
- `caseId`, `tenantId`는 콜백에서 보내지 않아도 됩니다. BE가 `runId`로 `case_analysis_run`을 조회해 사용합니다.

### 3.3 호출 시점

- **분석 완료 직후 단일 호출**입니다.
- 스트림 중간에 여러 번 호출하지 않습니다.
- `status=COMPLETED`이고 `finalResult`가 있으면 result·proposal을 저장합니다.

---

## 4. runId 전달 방식

| 방향 | 방식 |
|------|------|
| BE → Aura (트리거 시) | Request **body**의 `AuraAnalyzeRequest.runId` |
| Aura → BE (콜백 시) | Request **body**의 `AuraCallbackPayload.runId` |

- BE가 `runId`를 생성해 트리거 요청 body에 담아 Aura에 전달합니다.
- Aura는 콜백 시 같은 `runId`를 반드시 포함해야 합니다.

---

## 5. 스트림 제공 주체

| 항목 | 답변 |
|------|------|
| `GET /api/synapse/analysis-runs/{runId}/stream` | **BE 자체 생성** (Aura 프록시 아님) |
| 동작 | 1초마다 `case_analysis_run` 상태 폴링 → `started` / `completed` / `failed` SSE 발송 |
| Aura 호출 | BE가 Aura 스트림 엔드포인트를 호출하지 않음 |

**정리**
- BE 스트림: DB 상태 폴링 기반 단순 이벤트
- Aura 스트림: Aura가 자체 SSE 제공 (step, evidence, confidence, proposal 등)
- 현재는 둘 다 별도로 존재합니다. 프론트는 BE의 `/stream`을 사용하거나, Aura의 스트림을 직접 구독할 수 있습니다.

---

## 6. DEMO 모드 정책

| 구분 | 현재 | 제안 |
|------|------|------|
| BE | `synapse.demo-mode=true` → Aura 미호출, `completeDemoRun()` 실행 | |
| Aura | `DEMO_OFF` → `{"status":"disabled"}` 반환 | |
| 방향 | 각자 독립 | **통합 검토 권장** |

**제안**
- BE `demo-mode=true`면 Aura 트리거를 호출하지 않음 (현재 이미 그렇게 동작)
- Aura `DEMO_OFF`는 Aura 수신 요청에 대한 응답 정책
- 협의 후: 두 값을 하나의 "데모 모드"로 묶거나, BE가 Aura `DEMO_OFF` 응답을 보고 graceful fallback 하는 방식 검토 가능

---

## 7. action-proposals 생성 시점

| 질문 | 답변 |
|------|------|
| 생성 시점 | **Aura 콜백 수신 시점** |
| 조건 | `status=COMPLETED` && `finalResult.proposals` 존재 |
| 트리거 직후 | 생성하지 않음 |

**Aura 권장**
- 분석 완료 후, 콜백으로 `finalResult.proposals` 배열을 한 번에 전송하면 됩니다.

---

## 8. 추가 확인 답변 (Aura 2차 질의)

### 8.1 Aura 트리거 요청 스키마 (AuraAnalyzeRequest)

BE가 `POST /aura/cases/{caseId}/analysis-runs` 호출 시 **Request Body** 전체 구조:

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| runId | UUID | **필수** | BE가 생성한 run 식별자. 콜백 시 동일 값 반환 필요 |
| mode | String | 선택 | `LIVE` \| `SIMULATION` (기본: LIVE) |
| requestedBy | String | 선택 | `HUMAN` \| `SYSTEM` (기본: HUMAN) |
| options | Map\<String, Object\> | 선택 | 추가 옵션 (확장용) |

**추가 필드**  
- `tenantId`, `traceId` 등은 **Request Body에 없음**.
- `tenantId`는 **Header `X-Tenant-ID`**로 전달됨.
- `caseId`는 **Path**에 포함됨 (`/aura/cases/{caseId}/analysis-runs`).

**예시 (JSON)**
```json
{
  "runId": "550e8400-e29b-41d4-a716-446655440000",
  "mode": "LIVE",
  "requestedBy": "HUMAN",
  "options": null
}
```

---

### 8.2 finalResult 필드명

| Aura 용어 | BE 스키마 | 결론 |
|-----------|-----------|------|
| confidenceBreakdown | **confidence** | BE는 `confidence` 사용 |
| similarCases | **similar** | BE는 `similar` 사용 |

**Aura 권장**  
- `confidence` (confidenceBreakdown 대신)
- `similar` (similarCases 대신)

위 두 필드명으로 콜백 body를 보내면 됩니다.

---

### 8.3 BE trigger 호출 시 Aura 응답 형식

BE가 Feign으로 `POST /analysis-runs`를 호출할 때 **기대하는 응답 형태**:

| 선택 | 형태 | BE 기대 |
|------|------|---------|
| **A** | 202 Accepted + runId 등 JSON body 즉시 반환, 이후 백그라운드 분석 → 콜백 | ✅ **BE 기대 형태** |
| B | 분석 완료까지 동기 대기 후 단일 JSON | ❌ |
| C | SSE 스트리밍 | ❌ Feign이 처리 불가 |

**BE 현재 처리**
- `AuraAnalyzeResponse`: `{ status: String, streamUrl: String }` 를 JSON으로 파싱.
- Feign은 **동기 HTTP 응답**만 처리하므로, SSE 스트리밍은 사용 불가.

**권장**
- Aura에서 **(A) 형태**로 변경: `POST /analysis-runs` → **즉시 202 + JSON body** 반환.
- 이후 Aura가 백그라운드로 분석 수행 및 콜백 호출.
- JSON body 예시: `{ "status": "STARTED", "runId": "<uuid>", "streamUrl": "http://..." }` 등.

---

### 8.4 콜백 URL 설정

| 항목 | 값 |
|------|-----|
| 전체 URL | `{gateway_base}/api/synapse/internal/aura/callback` |
| 예시 (로컬) | `http://localhost:8080/api/synapse/internal/aura/callback` |
| 예시 (운영) | `https://{gateway-host}/api/synapse/internal/aura/callback` |

**base URL (`{gateway_host}:8080`) 출처**
- **BE에서 제공하지 않음**. Aura 설정(환경변수 등)으로 관리.
- 제안: Aura 측에 `DWP_GATEWAY_URL` 또는 `DWP_BE_CALLBACK_BASE` 같은 환경변수 도입.
  - 예: `DWP_GATEWAY_URL=http://localhost:8080` → 콜백 URL = `{DWP_GATEWAY_URL}/api/synapse/internal/aura/callback`

---

## 9. 추가 확인 답변 (Aura 3차 질의)

### 9.1 streamUrl 용도

| 질문 | 답변 |
|------|------|
| FE용 vs BE 내부용 | **FE용** – FE가 SSE 스트림을 구독할 때 사용 |
| BE 처리 | `AnalysisRunTriggerResponse.streamUrl`에 담아 FE에 전달 (BE 내부 사용 없음) |

**현재 흐름**
- BE 기본값: `/api/synapse/analysis-runs/{runId}/stream` (BE 자체 스트림)
- Aura가 `streamUrl`을 반환하면 BE가 그 값을 그대로 덮어써서 FE에 전달

**Aura streamUrl 형식 제안**
- `GET /aura/cases/{caseId}/analysis/stream?runId={runId}` 형식 가정
- Gateway 경유 시 FE 호출: `GET {gateway_base}/api/aura/cases/{caseId}/analysis/stream?runId={runId}`
- 상대 경로 예: `/api/aura/cases/{caseId}/analysis/stream?runId={runId}`  
  (FE가 gateway base를 붙여 호출)

---

### 9.2 status=FAILED 콜백 시 에러 정보

| 항목 | 현재 BE 스키마 |
|------|----------------|
| errorMessage | ❌ 없음 |
| stage | ❌ 없음 |
| 기타 에러 필드 | ❌ 없음 |

**현재 BE 처리**
- `status=FAILED`일 때 `run.error_message`에 `"Aura callback status: FAILED"` 고정값 저장

**partialEvents 사용**
- `partialEvents`는 BE에서 **파싱·처리하지 않음**. 포함해도 오류 없음.
- Aura는 선택적으로 `partialEvents`에 `{ "stage": "...", "errorMessage": "..." }` 형태로 에러 정보를 넣어도 됨 (향후 BE 확장 시 활용 가능).

**권장**
- 추후 BE에 `errorMessage` (또는 `error`) 필드를 추가해 `run.error_message`에 저장하는 방안 검토 가능

---

### 9.3 ProposalItem requiresApproval

| 항목 | BE 스키마 |
|------|-----------|
| requiresApproval | ❌ 없음 (ProposalItem / CaseActionProposal 모두) |

**결론**
- BE는 해당 필드를 사용하지 않음.
- Aura는 **선택적으로** 포함해도 됨. Jackson이 unknown property를 무시하므로 BE 동작에 영향 없음.

---

## 10. 백엔드 반영 사항

1. **AuraCaseTabClient**  
   - `POST /aura/cases/{caseId}/analysis-runs` (back.txt 권장, 반영 완료)

---

*작성일: 2025-02-09*
