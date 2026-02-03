# [FE→BE 요청] SynapseX 운영형 UX 마감 — 백엔드 작업 결과

> **작업 완료일**: 2026-02-03  
> **요청 문서**: `SYNAPSEX_UX_BACKEND_REQUESTS.md`

---

## 1. Observability — gateway_request_id / trace_id ✅

### 구현 내용

| 항목 | 상태 | 설명 |
|------|------|------|
| 응답 헤더 `X-Gateway-Request-Id` | ✅ | 모든 API 응답에 포함 (4xx/5xx 포함) |
| 응답 헤더 `X-Trace-Id` | ✅ | 동일 |

### 구현 위치

- **dwp-core**: `ResponseTraceHeaderFilter` — 요청 헤더에서 추출하여 응답 헤더로 전파
- **dwp-gateway**: `ApiCallHistoryFilter` — fallback으로 응답 헤더 추가 (백엔드 미도달 시)
- **CoreObservabilityAutoConfiguration**: `ResponseTraceHeaderFilter` 자동 등록

### FE 연동

- axios interceptor에서 `response.headers['x-trace-id']`, `response.headers['x-gateway-request-id']` 추출
- DevErrorPanel에 `gatewayRequestId`, `traceId` 표시

---

## 2. Action Center — auditId (실패 시) ✅

### 구현 내용

| API | 상태 | 실패 시 응답 |
|-----|------|--------------|
| POST /api/synapse/actions/{id}/simulate | ✅ | `auditId`, `traceId`, `gatewayRequestId` 포함 |
| POST /api/synapse/actions/{id}/approve | ✅ | 동일 |
| POST /api/synapse/actions/{id}/execute | ✅ | 동일 |

### 응답 형식

```json
{
  "status": "ERROR",
  "message": "액션을 찾을 수 없습니다.",
  "errorCode": "E1002",
  "auditId": "12345",
  "traceId": "trace-xyz789",
  "gatewayRequestId": "req-abc123",
  "timestamp": "2026-02-03T..."
}
```

### 구현 위치

- **ApiResponse**: `auditId` 필드 추가, `error(..., auditId, traceId, gatewayRequestId)` 오버로드
- **AuditWriter**: `logActionEventFailure()` — 실패 시 audit 기록 후 auditId 반환
- **ActionController**: simulate/approve/execute try-catch, 실패 시 audit 기록 + auditId 포함 응답

### FE 연동

- Action mutation `onError`에서 `res.auditId` 추출
- toast에 "Audit 상세 보기" 링크: `/synapse/audit?auditId={auditId}`

---

## 3. 목록 API — pagination / sort ✅ (이미 지원)

### 확인 결과

| API | page | size | sort | 비고 |
|-----|------|------|------|------|
| GET /api/synapse/anomalies | ✅ | ✅ | ✅ | `page`(0), `size`(20), `sort` 지원 |
| GET /api/synapse/archive | ✅ | ✅ | ✅ | 동일 |
| GET /api/synapse/documents | ✅ | ✅ | ✅ | 동일 |
| GET /api/synapse/actions | ✅ | ✅ | ✅ | 동일 |

### sort 파라미터 형식

- `sort`: 필드명 또는 `필드명,asc` / `필드명,desc`
- 예: `sort=detectedAt,desc`, `sort=createdAt`

### FE 연동

- 목록 API 호출 시 `page`, `size`, `sort` 파라미터 전달 가능

---

## 4. 요약

| 순위 | 항목 | BE 상태 | FE 대기 |
|------|------|---------|---------|
| 1 | gateway_request_id / trace_id (응답 헤더) | ✅ 완료 | 연동 가능 |
| 2 | auditId (Action 실패 응답) | ✅ 완료 | 연동 가능 |
| 3 | pagination/sort (목록 API) | ✅ 이미 지원 | 연동 가능 |
