# P0-4 감사 로그·agent-stream FE 확인 요청 — BE 답변

> **대상**: 프론트엔드  
> **목적**: 감사 추적 로그 진입 기본 필터·agent-stream 응답 스키마 확인 요청에 대한 BE 답변  
> **작성일**: 2026-02-10

---

## 2.1 감사 추적 로그 진입 시 기본 필터 (P0-4)

### FE 동작

- "전체 감사 로그 보기" 클릭 시 `/synapse/audit?range=6h&category=CASE` 로 이동합니다.

### BE 답변: 지원 여부 및 허용 값

**지원합니다.** 감사 API는 아래 쿼리 파라미터를 지원합니다.

| 파라미터 | 지원 | 비고 |
|----------|------|------|
| **range** | ✅ | `1h`, `6h`, `24h`, `7d`, `30d`, `90d` (단일 값). 미지정 시 기본 24h 구간 적용. |
| **category** | ✅ | **eventCategory** 와 동일하게 처리됩니다. `category` 와 `eventCategory` 둘 다 받으며, **category** 가 있으면 우선 사용. |
| **eventCategory** | ✅ | category 없을 때 사용. DB `event_category` 컬럼 필터. |
| **resourceType** | ✅ | DB `resource_type` 컬럼 필터. 케이스 관련만 보려면 `AGENT_CASE` 사용 가능. |

**category / eventCategory 허용 값** (event_category 코드):

| 값 | 설명 |
|----|------|
| `CASE` | 케이스 관련 이벤트 (FE에서 사용 중인 기본값과 동일) |
| `ACTION` | 액션(제안/승인/실행 등) |
| `AGENT` | 에이전트 활동 |
| `ADMIN` | 관리자 작업 |
| `POLICY` | 정책/가드레일/사전 등 |
| `AUDIT` | 감사 화면 조회 |
| `DASHBOARD` | 대시보드 조회 |
| `INTEGRATION` | 연동 이벤트 |
| `FEEDBACK` | 피드백 |
| `UI` | UI 클릭/필터 |
| `RUN` | Detect Run 등 |

**resourceType 허용 값** (resource_type, 예시):

| 값 | 설명 |
|----|------|
| `AGENT_CASE` | 케이스 리소스 (케이스 관련 감사만 볼 때 사용) |
| `CASE` | (동의어로 사용되는 경우 있음) |
| `AGENT_ACTION` | 액션 리소스 |
| `ACTION` | |
| `AUDIT_EVENT` | 감사 이벤트 자체 |
| `DOCUMENT`, `OPEN_ITEM`, `DETECT_RUN` 등 | 기타 리소스 타입 |

**FE 링크 유지 권장**

- `/synapse/audit?range=6h&category=CASE` → **그대로 사용 가능.** BE에서 `category=CASE` 로 event_category 필터 적용.
- 케이스 리소스 기준으로 더 좁히고 싶으면 `resourceType=AGENT_CASE` 추가 가능 (예: `?range=6h&category=CASE&resourceType=AGENT_CASE`). 선택 사항.

**미지원 시 대안**  
해당 없음. 위 파라미터 모두 지원하므로 FE는 동일 링크 유지·range/category 기본값 세팅으로 P0 요구사항 충족 가능합니다.

---

## 2.2 agent-stream 응답 스키마 (참고)

### API

- `GET /api/synapse/dashboard/agent-stream` (또는 `/agent-activity`)
- 쿼리: `range` (기본 `6h`), `limit` (기본 `50`)

### 응답 구조

```json
{
  "success": true,
  "data": {
    "range": "6h",
    "items": [
      {
        "ts": "2026-02-10T12:00:00Z",
        "level": "INFO",
        "stage": "SCAN",
        "message": "[SCAN] ...",
        "caseId": "CS-2026-0001",
        "actionId": null,
        "resourceType": "AGENT_CASE",
        "resourceId": "12345",
        "traceId": null,
        "gatewayRequestId": null,
        "links": {
          "casePath": "/cases/12345",
          "auditPath": "/audit?resourceType=AGENT_CASE&resourceId=12345"
        }
      }
    ]
  }
}
```

### data.items[] 필드 매핑 (FE 가정 대조)

| FE 가정 | BE 필드 | 비고 |
|---------|---------|------|
| ts 또는 timestamp | **ts** (Instant, ISO-8601) | ✅ 제공. `timestamp` 는 없음 → `ts` 사용. |
| stage 또는 action | **stage** | ✅ 제공. SCAN, DETECT, ANALYZE, SIMULATE, EXECUTE, MATCH 등. `action` 필드는 없음. |
| message | **message** | ✅ 제공. |
| status | (없음) | BE에는 별도 `status` 필드 없음. `level`(INFO/WARN/ERROR)로 심각도 표현. |
| caseId (선택) | **caseId** | ✅ 제공. 표시용 형식(예: CS-2026-0001). |
| caseKey (선택) | (없음) | **caseKey** 는 없음. `caseId` + `resourceId` 로 대응 가능. |

**eventType 라벨**

- BE는 현재 **eventType** 필드를 agent-stream items에 내려주지 **않습니다.**  
- **stage** 값이 event_category/event_type 기반으로 매핑된 결과입니다 (SCAN, DETECT, ANALYZE, SIMULATE, EXECUTE 등).
- FE에서 `stage`(및 필요 시 `message`)를 이용해 `analysis_started`, `analysis_step`, `analysis_completed`, `proposal_created`, `execute_started` 등 읽기 쉬운 라벨로 매핑하는 방식 유지 가능합니다. 그 외는 원문 표시해도 무방합니다.
- **선택**: 이후 BE에서 `eventType` 등 공통 코드를 내려주기로 하면, FE에서 해당 필드 우선 사용하도록 변경하면 됩니다.

---

**요약**

- **2.1** 감사 API는 `range`, `category`(또는 `eventCategory`), `resourceType` 지원. `/synapse/audit?range=6h&category=CASE` 유지·기본값 세팅으로 P0-4 충족 가능.
- **2.2** agent-stream은 `data.items[]` 에 `ts`, `stage`, `message`, `level`, `caseId`, `actionId`, `resourceType`, `resourceId`, `links` 제공. `timestamp`/`action`/`status`/`caseKey` 는 없으며, FE fallback(ts 사용, stage→라벨 매핑)으로 처리 가능.
