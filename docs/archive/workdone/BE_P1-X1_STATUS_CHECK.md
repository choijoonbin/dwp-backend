# BE P1-X.1 현황 점검 결과

**작성일**: 2026-01-20  
**목적**: 중복 작업 방지 및 개선 방향 명시

---

## ✅ 현황 점검 결과

### 1) Gateway 라우팅
- ✅ **구현됨**: `/api/aura/**` → `Aura-Platform(9000)` 라우팅 존재
- 파일: `dwp-gateway/src/main/resources/application.yml` (라인 38-54)
- 필터: `StripPrefix=1`, `PreserveHostHeader`

### 2) SSE 설정
- ✅ **구현됨**: `response-timeout: 300s` 설정됨
- ✅ **구현됨**: `SseResponseHeaderFilter`가 SSE 응답 헤더 보장
  - `Content-Type: text/event-stream`
  - `Cache-Control: no-cache`
  - `Connection: keep-alive`
  - `X-Accel-Buffering: no`
- ⚠️ **보강 필요**: 응답 Content-Type도 SSE 요청 식별에 활용

### 3) ApiCallHistoryFilter
- ✅ **구현됨**: SSE 요청 요약 기록 정책 이미 적용됨
  - SSE 요청은 `queryString`, `requestSizeBytes`, `responseSizeBytes` 제외
  - 요약 정보만 기록 (path, statusCode, latency, tenantId, userId, agentId)
- ⚠️ **보강 필요**: 
  - 응답 Content-Type 확인 추가
  - failure_reason 필드 추가 (선택)
  - MDC 로깅 컨텍스트 추가

### 4) CORS 설정
- ✅ **구현됨**: `Last-Event-ID`, `X-Agent-ID` 포함됨
- 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/CorsConfig.java`

### 5) HeaderPropagationFilter
- ✅ **구현됨**: 필수 헤더 전파 보장
- 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/HeaderPropagationFilter.java`

### 6) traceId 생성
- ✅ **구현됨**: `ApiCallHistoryFilter`에서 traceId 생성 및 저장
- ⚠️ **보강 필요**: MDC에 traceId, tenantId, userId, agentId 설정

---

## 📋 개선 방향

### 1) SSE 요청 식별 강화
- 현재: Accept 헤더 또는 `/stream` 경로로만 확인
- 개선: 응답 `Content-Type: text/event-stream`도 확인

### 2) Observability 강화
- MDC에 traceId, tenantId, userId, agentId 설정
- 로그에 컨텍스트 정보 자동 포함

### 3) failure_reason 필드 추가 (선택)
- 상태코드 499, 504 등 비정상 종료 시 원인 기록

### 4) 테스트 작성
- `ApiCallHistoryFilterTest` 작성
- SSE 요청 요약 기록 검증

### 5) 문서 업데이트
- Admin Monitoring API 스펙에 SSE 요약 기록 정책 명시

---

**점검 완료일**: 2026-01-20  
**다음 작업**: 1) SSE 요청 요약 로그 정책 강화
