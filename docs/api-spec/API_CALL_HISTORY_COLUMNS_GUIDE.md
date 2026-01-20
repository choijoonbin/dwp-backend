# API 호출 이력 컬럼 가이드

**작성일**: 2026-01-20  
**목적**: `sys_api_call_histories` 테이블의 NULL 컬럼(`agent_id`, `query_string`, `request_size_bytes`, `response_size_bytes`) 용도 및 수집 방법 가이드

---

## 📊 컬럼 용도 및 현재 상태

### 1. `agent_id` (VARCHAR(100), nullable)

**용도**:
- **Aura AI 에이전트 세션/클라이언트 식별자**
- Aura-Platform에서 호출하는 API 요청의 에이전트 세션 ID를 추적
- 예: `agent_session_abc123`, `aura_client_xyz789`

**현재 상태**: ❌ **수집되지 않음**

**수집 방법**:
- Gateway에서 `X-Agent-ID` 헤더를 읽어서 저장해야 함
- 프론트엔드에서 Aura 에이전트 요청 시 `X-Agent-ID` 헤더를 전송해야 함

**사용 사례**:
- 에이전트별 API 호출 패턴 분석
- 에이전트 세션 추적 및 디버깅
- 에이전트별 성능 모니터링

---

### 2. `query_string` (TEXT, nullable)

**용도**:
- **HTTP 요청의 쿼리스트링 (Query Parameters)**
- 예: `/api/admin/users?page=1&size=10` → `page=1&size=10`
- 예: `/api/monitoring/events?from=2026-01-01&to=2026-01-31` → `from=2026-01-01&to=2026-01-31`

**현재 상태**: ⚠️ **부분 수집** (Gateway에서 `request.getURI().getQuery()`로 수집 시도 중)

**수집 방법**:
- Gateway의 `ApiCallHistoryFilter`에서 이미 수집 로직이 있음
- 하지만 실제로 NULL인 경우가 많음 (쿼리스트링이 없는 요청이 많기 때문)

**확인 필요 사항**:
- 쿼리스트링이 있는 요청에서도 NULL인지 확인 필요
- Gateway 필터에서 쿼리스트링 추출 로직이 정상 동작하는지 확인

**사용 사례**:
- API 호출 패턴 분석 (어떤 파라미터로 호출되는지)
- 필터링/검색 쿼리 분석
- API 사용 패턴 통계

---

### 3. `request_size_bytes` (BIGINT, nullable)

**용도**:
- **HTTP 요청 본문(Body) 크기 (바이트 단위)**
- POST/PUT 요청의 페이로드 크기 측정
- 예: `{"username": "admin", "password": "..."}` → 약 50 bytes

**현재 상태**: ❌ **수집되지 않음**

**수집 방법**:
- Gateway에서 요청 본문 크기를 측정해야 함
- Spring Cloud Gateway (Reactive)에서는 본문을 읽으면 소비되므로, 크기 측정을 위해 특별한 처리가 필요
- 방법 1: `Content-Length` 헤더 확인 (가장 간단)
- 방법 2: 요청 본문을 버퍼링하여 크기 측정 (메모리 사용 증가)

**사용 사례**:
- API 요청 크기 모니터링
- 대용량 요청 감지 및 제한
- 네트워크 트래픽 분석
- 성능 최적화 (요청 크기별 응답 시간 분석)

---

### 4. `response_size_bytes` (BIGINT, nullable)

**용도**:
- **HTTP 응답 본문(Body) 크기 (바이트 단위)**
- API 응답 데이터 크기 측정
- 예: `{"status": "SUCCESS", "data": [...]}` → 약 500 bytes

**현재 상태**: ❌ **수집되지 않음**

**수집 방법**:
- Gateway에서 응답 본문 크기를 측정해야 함
- Spring Cloud Gateway (Reactive)에서는 응답 본문을 읽으면 소비되므로, 크기 측정을 위해 특별한 처리가 필요
- 방법 1: `Content-Length` 응답 헤더 확인 (가장 간단)
- 방법 2: 응답 본문을 버퍼링하여 크기 측정 (메모리 사용 증가)

**사용 사례**:
- API 응답 크기 모니터링
- 대용량 응답 감지 및 최적화
- 네트워크 트래픽 분석
- 성능 최적화 (응답 크기별 전송 시간 분석)

---

## 🔧 백엔드 수정 완료 사항

### ✅ 1. Gateway 필터 수정 완료 (`ApiCallHistoryFilter.java`)

#### ✅ `agent_id` 수집 추가 완료
```java
String agentId = request.getHeaders().getFirst("X-Agent-ID");
// Gateway가 프론트엔드로부터 받은 X-Agent-ID 헤더를 읽어서 저장
```

#### ✅ `request_size_bytes` 수집 추가 완료
```java
// Content-Length 헤더 확인 (권장 방법)
String requestContentLength = request.getHeaders().getFirst("Content-Length");
Long requestSizeBytes = null;
if (requestContentLength != null) {
    try {
        requestSizeBytes = Long.parseLong(requestContentLength);
    } catch (NumberFormatException e) {
        // 무시
    }
}
```

#### ✅ `response_size_bytes` 수집 추가 완료
```java
// Content-Length 응답 헤더 확인 (권장 방법)
String responseContentLength = exchange.getResponse().getHeaders().getFirst("Content-Length");
Long responseSizeBytes = null;
if (responseContentLength != null) {
    try {
        responseSizeBytes = Long.parseLong(responseContentLength);
    } catch (NumberFormatException e) {
        // 무시
    }
}
```

#### ✅ `query_string` 수집 확인 완료
```java
// 이미 구현되어 있음
String queryString = request.getURI().getQuery();
// queryString이 null인 경우도 정상 (쿼리스트링이 없는 요청)
```

### ✅ 2. DTO 수정 완료 (`ApiCallHistoryRequest.java`)

```java
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiCallHistoryRequest {
    private Long tenantId;
    private Long userId;
    private String agentId;  // ✅ 추가 완료
    private String method;
    private String path;
    private String queryString;
    private Integer statusCode;
    private Long latencyMs;
    private Long requestSizeBytes;  // ✅ 추가 완료
    private Long responseSizeBytes;  // ✅ 추가 완료
    private String ipAddress;
    private String userAgent;
    private String traceId;
    private String errorCode;
    private String source;
}
```

### ✅ 3. Service 수정 완료 (`MonitoringService.java`)

```java
ApiCallHistory history = ApiCallHistory.builder()
    .tenantId(request.getTenantId())
    .userId(request.getUserId())
    .agentId(request.getAgentId())  // ✅ 추가 완료
    .method(request.getMethod())
    .path(request.getPath())
    .queryString(request.getQueryString())
    .statusCode(request.getStatusCode())
    .latencyMs(request.getLatencyMs())
    .requestSizeBytes(request.getRequestSizeBytes())  // ✅ 추가 완료
    .responseSizeBytes(request.getResponseSizeBytes())  // ✅ 추가 완료
    .ipAddress(request.getIpAddress())
    .userAgent(request.getUserAgent())
    .traceId(request.getTraceId())
    .errorCode(request.getErrorCode())
    .source(request.getSource())
    .build();
```

### 📌 Gateway 헤더 전파 확인

Gateway는 이미 `X-Agent-ID` 헤더를 Aura-Platform으로 전파하도록 설정되어 있습니다:
- `HeaderPropagationFilter`: `X-Agent-ID` 헤더 확인 및 로깅
- `CorsConfig`: `X-Agent-ID`를 허용된 헤더로 설정
- Spring Cloud Gateway: 기본적으로 모든 헤더를 다운스트림 서비스로 전파

---

## 📱 프론트엔드 작업 필요 사항

### 1. `X-Agent-ID` 헤더 전송

**중요**: 프론트엔드는 **Gateway(`http://localhost:8080`)를 통해** Aura-Platform과 통신합니다.
- 프론트엔드 → Gateway (`/api/aura/**`) → Aura-Platform (포트 9000)
- Gateway가 자동으로 헤더를 Aura-Platform으로 전파합니다.

**Aura AI 에이전트 요청 시**:
- 프론트엔드에서 Gateway로 요청할 때 `X-Agent-ID` 헤더를 추가해야 함
- 에이전트 세션 ID 또는 클라이언트 ID를 헤더 값으로 전송
- Gateway가 이 헤더를 Aura-Platform으로 전파합니다

**예시**:
```typescript
// Aura 에이전트 요청 예시 (Gateway를 통해)
const agentId = 'agent_session_' + sessionId; // 또는 고유한 에이전트 ID

// 프론트엔드는 Gateway(8080)로 요청
fetch('http://localhost:8080/api/aura/test/stream', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'X-Tenant-ID': tenantId,
    'X-User-ID': userId,
    'X-Agent-ID': agentId,  // ⭐ 추가 필요
    'X-DWP-Source': 'AURA',
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({ prompt: '...' })
});

// Gateway가 자동으로 Aura-Platform(9000)으로 라우팅하면서 헤더 전파
```

**전송 시점**:
- Aura 에이전트와 통신하는 모든 API 요청에 포함 (`/api/aura/**` 경로)
- 에이전트 세션이 시작될 때 생성된 고유 ID 사용
- 세션 종료 시까지 동일한 ID 유지

---

### 2. `Content-Length` 헤더 확인 (선택사항)

**요청 본문 크기 측정**:
- 프론트엔드에서 POST/PUT 요청 시 `Content-Length` 헤더가 자동으로 설정됨
- 백엔드에서 이 헤더를 읽어서 `request_size_bytes`에 저장
- **프론트엔드 추가 작업 불필요** (브라우저가 자동 처리)

**응답 본문 크기 측정**:
- 백엔드에서 응답 시 `Content-Length` 헤더를 설정하면 Gateway가 읽어서 저장
- **프론트엔드 추가 작업 불필요**

---

## 📋 작업 체크리스트

### 백엔드 작업
- [x] Gateway `ApiCallHistoryFilter`에 `agent_id` 수집 로직 추가
- [x] Gateway `ApiCallHistoryFilter`에 `request_size_bytes` 수집 로직 추가 (Content-Length 헤더 확인)
- [x] Gateway `ApiCallHistoryFilter`에 `response_size_bytes` 수집 로직 추가 (Content-Length 헤더 확인)
- [x] `ApiCallHistoryRequest` DTO에 `agentId`, `requestSizeBytes`, `responseSizeBytes` 필드 추가
- [x] `MonitoringService.recordApiCallHistory()` 메서드에 새 필드 저장 로직 추가
- [x] `query_string` 수집 로직 정상 동작 확인 (이미 구현되어 있음)

### 프론트엔드 작업
- [ ] Aura 에이전트 요청 시 `X-Agent-ID` 헤더 전송 로직 추가
- [ ] 에이전트 세션 ID 생성 및 관리 로직 구현
- [ ] 에이전트 세션 시작 시 고유 ID 생성
- [ ] 에이전트 세션 종료 시까지 동일 ID 유지

---

## 🧪 테스트 방법

### 1. `agent_id` 테스트

**요청 예시**:
```bash
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "X-Agent-ID: agent_test_123" \
  -H "X-DWP-Source: AURA" \
  -H "Content-Type: application/json" \
  -d '{"prompt": "test"}'
```

**확인 쿼리**:
```sql
SELECT api_call_history_id, agent_id, path, created_at
FROM sys_api_call_histories
WHERE agent_id = 'agent_test_123'
ORDER BY created_at DESC
LIMIT 10;
```

### 2. `request_size_bytes` 테스트

**요청 예시**:
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -d '{"username": "admin", "password": "admin1234!", "tenantId": 1}'
```

**확인 쿼리**:
```sql
SELECT api_call_history_id, path, request_size_bytes, created_at
FROM sys_api_call_histories
WHERE path = '/api/auth/login'
  AND request_size_bytes IS NOT NULL
ORDER BY created_at DESC
LIMIT 10;
```

### 3. `response_size_bytes` 테스트

**확인 쿼리**:
```sql
SELECT api_call_history_id, path, status_code, response_size_bytes, created_at
FROM sys_api_call_histories
WHERE response_size_bytes IS NOT NULL
ORDER BY created_at DESC
LIMIT 10;
```

### 4. `query_string` 테스트

**요청 예시**:
```bash
curl -X GET "http://localhost:8080/api/admin/users?page=1&size=10" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1"
```

**확인 쿼리**:
```sql
SELECT api_call_history_id, path, query_string, created_at
FROM sys_api_call_histories
WHERE path = '/api/admin/users'
  AND query_string IS NOT NULL
ORDER BY created_at DESC
LIMIT 10;
```

---

## ⚠️ 주의사항

### 1. `Content-Length` 헤더 제한

- **청크 전송(Chunked Transfer)**: `Transfer-Encoding: chunked`를 사용하는 경우 `Content-Length` 헤더가 없을 수 있음
- **스트리밍 응답**: SSE 스트리밍 응답의 경우 `Content-Length` 헤더가 없을 수 있음
- **압축 응답**: `Content-Encoding: gzip` 등을 사용하는 경우, `Content-Length`는 압축된 크기임

**대안**:
- 청크 전송/스트리밍의 경우 `request_size_bytes`와 `response_size_bytes`를 NULL로 저장 (정상)
- 필요 시 본문을 버퍼링하여 실제 크기 측정 (메모리 사용 증가)

### 2. `agent_id` 헤더 전송

- **필수 아님**: 일반 사용자 요청에는 `X-Agent-ID` 헤더가 없어도 됨 (NULL 저장)
- **Aura 에이전트 요청에만 필요**: Aura-Platform과 통신하는 요청에만 전송

### 3. `query_string` NULL 처리

- **정상 동작**: 쿼리스트링이 없는 요청은 NULL이 정상
- **주의**: 쿼리스트링이 있는 요청에서도 NULL인 경우, Gateway 필터 로직 확인 필요

---

## 📚 관련 문서

- [Admin 모니터링 API 스펙](./ADMIN_MONITORING_API_SPEC.md)
- [API 호출 이력 조회 API](./ADMIN_MONITORING_API_SPEC.md#api-호출-이력-조회)
- [Gateway 헤더 전파 가이드](../README.md#표준-헤더-strict-header-contract)

---

**문서 작성일**: 2026-01-20  
**작성자**: DWP Backend Team
