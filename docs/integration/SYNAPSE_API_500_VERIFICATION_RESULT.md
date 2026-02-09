# Synapse API 500 오류 확인 결과 (Aura 확인 요청 응답)

> **작성일**: 2026-02-06  
> **대상**: Aura 팀  
> **배경**: Finance Agent 스트리밍 시 Synapse API 500 발생

---

## 1. 요약

| 확인 항목 | 결과 |
|----------|------|
| **경로 불일치** | ⚠️ Aura 호출 경로(`/tools/finance/**`)와 Synapse 제공 경로(`/api/synapse/agent-tools/**`)가 **다름** |
| **요청 형식 불일치** | ⚠️ search_documents, get_open_items: Aura는 POST+body, Synapse는 GET+query |
| **caseId 지원** | ⚠️ documents/open-items에 caseId 기반 조회 없음 |
| **lineage 파라미터** | ⚠️ belnr/gjahr/bukrs 직접 전달 시 미지원 (caseId 또는 docKey만 지원) |
| **agent/events 스키마** | ✅ Synapse 기대 형식과 호환 |

---

## 2. API 경로 및 계약 비교

### 2.1 get_case (케이스 조회)

| 항목 | Aura 호출 | Synapse 제공 |
|------|----------|-------------|
| **경로** | `GET /tools/finance/cases/{caseId}` | `GET /api/synapse/agent-tools/cases/{caseId}` |
| **메서드** | GET | GET ✅ |
| **caseId** | 85114 | Long path variable ✅ |

**권장**: Aura Base URL을 `http://{gateway}:8080/api/synapse/agent-tools`로 설정하고,  
경로를 `cases/{caseId}`로 호출.

---

### 2.2 search_documents (문서 검색)

| 항목 | Aura 호출 | Synapse 제공 |
|------|----------|-------------|
| **경로** | `POST /tools/finance/documents/search` | `GET /api/synapse/agent-tools/documents` |
| **메서드** | POST | GET ❌ |
| **Body** | `{"caseId": "85114", "topK": 10}` | - |
| **Query** | - | bukrs, gjahr, vendorId, customerId, fromDate, toDate, amountMin, amountMax, anomalyFlags, page, size, sort |

**Gap**:
- Synapse는 **POST /documents/search** 없음
- **caseId 기반 문서 검색** 미지원 (case → docKey 변환 후 documents 조회 필요)
- **topK** 파라미터 없음 (page, size로 대체)

**500 원인 추정**: 404(경로 없음) 또는 405(Method Not Allowed) → 일부 프록시/클라이언트에서 500으로 변환될 수 있음.

---

### 2.3 get_open_items (미결 항목 조회)

| 항목 | Aura 호출 | Synapse 제공 |
|------|----------|-------------|
| **경로** | `POST /tools/finance/open-items/search` | `GET /api/synapse/agent-tools/open-items` |
| **메서드** | POST | GET ❌ |
| **Body** | `{"caseId": "85114"}` | - |
| **Query** | - | type (AR\|AP), overdueBucket, page, size, sort |

**Gap**:
- Synapse는 **POST /open-items/search** 없음
- **caseId 기반 open-items 필터** 미지원 (PHASE 문서에는 caseId 지원 계획 있음)

**500 원인 추정**: 404/405 → 500 변환 가능성.

---

### 2.4 get_lineage (라인리지 조회)

| 항목 | Aura 호출 | Synapse 제공 |
|------|----------|-------------|
| **경로** | `GET /tools/finance/lineage?caseId=85114` 또는 `?belnr=xxx&gjahr=xxx&bukrs=xxx` | `GET /api/synapse/agent-tools/lineage?caseId={caseId}` |
| **메서드** | GET | GET ✅ |
| **Query** | caseId 또는 belnr, gjahr, bukrs | **caseId (필수, Long)** |

**Gap**:
- **belnr, gjahr, bukrs** 직접 전달 시 Synapse **agent-tools/lineage**는 **미지원**
- LineageQueryService 내부적으로는 docKey(bukrs-belnr-gjahr) 지원하나, AgentToolController는 **caseId만** 노출
- LLM이 Field 메타데이터 문자열을 belnr/gjahr/bukrs로 잘못 전달한 경우 → caseId 누락 → 400 Bad Request (또는 타입 변환 실패 시 500)

**Synapse 개선 제안** (아래 §5 참조):
- lineage에 `docKey` 또는 `belnr`, `gjahr`, `bukrs` query 파라미터 추가
- 파라미터 검증 실패 시 명확한 400 에러 메시지 반환

---

### 2.5 agent/events (Agent Stream push)

| 항목 | Aura 호출 | Synapse 기대 |
|------|----------|-------------|
| **경로** | `POST /api/synapse/agent/events` | `POST /synapse/agent/events` (Gateway StripPrefix 후) ✅ |
| **Body** | `{"events": [...]}` | `{"events": [AgentEventItem, ...]}` ✅ |

**AgentEventItem 스키마** (Synapse):

| 필드 | 타입 | 필수 | 비고 |
|------|------|------|------|
| tenantId | string | ✅ | |
| timestamp | string | ✅ | ISO 8601 |
| stage | string | ✅ | SCAN\|DETECT\|EXECUTE\|SIMULATE\|ANALYZE\|MATCH |
| message | string | ✅ | |
| caseKey | string | | snake_case: case_key |
| caseId | string | | snake_case: case_id |
| severity | string | | INFO\|WARN\|ERROR |
| traceId | string | | snake_case: trace_id |
| actionId | string | | snake_case: action_id |
| payload | object | | |

**500 원인 가능성**:
- events 배열이 null/빈 배열 → `@NotNull` 위반 → 400
- 개별 event에 tenantId/timestamp/stage/message 누락 → `@Valid` 위반 → 400
- Content-Type이 application/json이 아님 → 415 또는 500

---

## 3. 포트 및 라우팅

| 구성요소 | 포트 | 비고 |
|----------|------|------|
| **Gateway** | 8080 | 외부 진입점, Aura는 반드시 Gateway 경유 권장 |
| **Synapsex** | 8085 | 내부 서비스 |
| **Main Service** | 8081 | HITL 등 |
| **Aura Platform** | 9000 | Python |

Aura 문서의 `http://localhost:8081`은 **Main Service** 포트입니다.  
Synapse API 호출 시에는 **Gateway 8080**을 사용해야 합니다.

**권장 Base URL**: `http://localhost:8080/api/synapse/agent-tools`  
(또는 `http://{gateway-host}:8080/api/synapse/agent-tools`)

---

## 4. 필수 헤더 검증

Synapse Agent Tool API는 다음 헤더를 **필수**로 요구합니다:

| 헤더 | 필수 | 비고 |
|------|------|------|
| **X-Tenant-ID** | ✅ | Long (숫자). 누락 시 400 |
| **Authorization** | ✅ | Bearer JWT (Gateway/JWT 필터 적용 시) |
| X-User-ID | - | 선택 |
| X-Agent-ID | - | 선택 (감사 시) |
| X-Trace-ID | - | 선택 (추적용) |

`X-Tenant-ID` 누락 시 `ErrorCode.AUTH_REQUIRED`로 400 반환.

---

## 5. Synapse 측 개선 (get_lineage) — ✅ 적용 완료

### 5.1 docKey / belnr,gjahr,bukrs 지원

AgentToolController.getLineage에 다음 파라미터를 추가했습니다:

```
GET /agent-tools/lineage?caseId={caseId}              # 기존
GET /agent-tools/lineage?docKey={bukrs-belnr-gjahr}  # 추가
GET /agent-tools/lineage?bukrs=x&belnr=y&gjahr=z      # 추가 (docKey 조합)
```

### 5.2 파라미터 검증 및 에러 메시지

- caseId, docKey, (bukrs+belnr+gjahr) 모두 없으면 → 400 +  
  `"caseId, docKey(bukrs-belnr-gjahr), 또는 bukrs+belnr+gjahr 조합이 필요합니다. Field 메타데이터 문자열을 전달하지 마세요."`

---

## 6. 500 원인 디버깅 체크리스트

1. **Synapse 서버 로그**  
   - 500 발생 시점의 스택 트레이스 확인  
   - `[com.dwp.services.synapsex]` 로그 레벨 DEBUG 권장

2. **케이스 85114 존재 여부**  
   ```sql
   SELECT case_id, tenant_id, status FROM dwp_aura.agent_case WHERE case_id = 85114;
   ```

3. **헤더**  
   - X-Tenant-ID, Authorization 전달 여부  
   - X-Tenant-ID가 해당 tenant의 caseId와 일치하는지

4. **경로/메서드**  
   - `/tools/finance/**` → 404 가능성 (Gateway에 해당 route 없음)  
   - POST `/documents/search`, POST `/open-items/search` → 405 가능성

5. **agent/events**  
   - Request body가 `{"events": [...]}` 형태인지  
   - 각 event에 tenantId, timestamp, stage, message 존재 여부

---

## 7. Aura 측 권장 조치

| 조치 | 내용 |
|------|------|
| **Base URL** | `http://{gateway}:8080` (8081 아님) |
| **경로** | `/api/synapse/agent-tools/**` 사용 (`/tools/finance/**` 대신) |
| **get_case** | `GET /api/synapse/agent-tools/cases/{caseId}` |
| **search_documents** | `GET /api/synapse/agent-tools/documents?page=0&size={topK}` + case의 bukrs/belnr/gjahr로 필터 (또는 Synapse에 caseId 기반 documents API 추가 요청) |
| **get_open_items** | `GET /api/synapse/agent-tools/open-items?page=0&size=20` (caseId 필터는 추후 Synapse 확장 시 적용) |
| **get_lineage** | `GET /api/synapse/agent-tools/lineage?caseId={caseId}` (caseId 필수, belnr/gjahr/bukrs는 현재 미지원) |
| **agent/events** | `POST /api/synapse/agent/events` + `{"events": [{tenantId, timestamp, stage, message, ...}]}` |

---

## 8. 관련 문서

- `AGENT_TOOL_API_SPEC.md` — Agent Tool API 명세
- `AURA_AGENT_STREAM_CONFIRMATION.md` — Agent Stream REST push 확인
- `AGENT_STREAM_REST_PUSH_result.md` — agent/events 상세 스키마
