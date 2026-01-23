# BE P1-X: Aura 통신 Gateway 단일 경유 강제 + SSE 안정화 + 헤더 계약 확정

**작성일**: 2026-01-20  
**작업 완료일**: 2026-01-20  
**상태**: ✅ 완료

---

## 📋 작업 개요

Aura-Platform(Python/FastAPI, 9000)과 Frontend 사이의 통신을 반드시 Gateway(8080) 경유로 고정하고, SSE 스트리밍 품질을 운영 수준으로 안정화했습니다.

---

## ✅ 완료된 작업

### 0) 중복/현황 체크 및 문서화
- ✅ Gateway routing: `/api/aura/**` → `9000` 라우팅 존재 확인
- ✅ SSE 타임아웃: `300s` 설정 확인
- ✅ CORS allowed headers: `Last-Event-ID`, `X-Agent-ID` 포함 확인
- ✅ HeaderPropagationFilter 존재 확인
- ✅ ApiCallHistoryFilter 동작 확인
- 📄 문서: `docs/AURA_GATEWAY_SINGLE_POINT_CHECKLIST.md`

### 1) Gateway SSE 계약 강화
- ✅ **SSE 응답 헤더 보장**:
  - `Content-Type: text/event-stream`
  - `Cache-Control: no-cache`
  - `Connection: keep-alive`
  - `X-Accel-Buffering: no` (Nginx 프록시 환경 대비)
- ✅ **타임아웃 설정**: `response-timeout: 300s`, `connect-timeout: 10s`
- ✅ **POST SSE 공식 지원**: Gateway가 POST 요청 body를 Aura-Platform으로 전달
- 📄 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/SseResponseHeaderFilter.java`

### 2) 헤더 Contract 강제/정리
- ✅ **RequiredHeaderFilter 신규 생성**:
  - `X-Tenant-ID` 필수 검증 (없으면 400 Bad Request)
  - `X-DWP-Source` 기본값 설정 (`FRONTEND`)
  - `X-DWP-Caller-Type` 기본값 설정 (`USER`)
  - 공개 API 제외 (`/api/auth/login`, `/api/monitoring/**` 등)
- ✅ **헤더 전파 보장**: `HeaderPropagationFilter`가 모든 표준 헤더 전파
- 📄 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/RequiredHeaderFilter.java`

### 3) SSE Event ID / Resume 지원
- ✅ **Last-Event-ID 헤더 전파**: Gateway가 `Last-Event-ID` 헤더를 Aura-Platform으로 전파
- ✅ **Event ID 생성**: `SseReconnectionFilter`가 SSE 응답에 `id:` 라인 추가
- 📄 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/SseReconnectionFilter.java` (이미 구현됨)

### 4) API Call History 정책
- ✅ **SSE 요청 요약 기록**: SSE 요청은 요약 정보만 기록 (path, statusCode, latency, tenantId, userId, agentId)
- ✅ **제외 항목**: queryString, requestSizeBytes, responseSizeBytes (스트리밍이므로 의미 없음)
- ✅ **목적**: 장시간 스트리밍으로 인한 과도한 로그 방지
- 📄 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/ApiCallHistoryFilter.java`

### 5) Aura 라우팅과 Auth 흐름
- ✅ **확장 포인트 마련**: `application.yml`에 TODO 주석 추가
- ✅ **현재 구조**: Gateway가 `Authorization` 헤더를 그대로 전파
- ✅ **향후 확장**: Gateway에서 JWT 검증 후 Aura-Platform으로 전달 가능
- 📄 파일: `dwp-gateway/src/main/resources/application.yml`

### 6) 테스트/검증
- ✅ **WebTestClient 테스트**: `SseStreamingTest.java` 신규 생성
  - SSE 응답 헤더 검증
  - 필수 헤더 검증 (X-Tenant-ID)
  - 타임아웃 설정 확인
  - Last-Event-ID 헤더 전파 확인
- 📄 파일: `dwp-gateway/src/test/java/com/dwp/gateway/integration/SseStreamingTest.java`

### 7) 문서 업데이트
- ✅ **README.md 업데이트**: Gateway 단일 경유 강조, 필수 헤더 명시
- ✅ **AURA_PLATFORM_INTEGRATION_GUIDE.md 업데이트**: Gateway 단일 경유 원칙 추가
- ✅ **AURA_GATEWAY_SINGLE_POINT_SPEC.md 신규 생성**: 상세 명세서 작성
- ✅ **AURA_GATEWAY_SINGLE_POINT_CHECKLIST.md 신규 생성**: 체크리스트 작성

---

## 📁 변경된 파일 목록

### 신규 생성 파일
1. `dwp-gateway/src/main/java/com/dwp/gateway/config/RequiredHeaderFilter.java`
2. `dwp-gateway/src/test/java/com/dwp/gateway/integration/SseStreamingTest.java`
3. `docs/AURA_GATEWAY_SINGLE_POINT_SPEC.md`
4. `docs/AURA_GATEWAY_SINGLE_POINT_CHECKLIST.md`
5. `docs/BE_P1-X_AURA_GATEWAY_SINGLE_POINT_SUMMARY.md`

### 수정된 파일
1. `dwp-gateway/src/main/java/com/dwp/gateway/config/SseResponseHeaderFilter.java`
   - `Connection: keep-alive` 헤더 추가
   - `X-Accel-Buffering: no` 헤더 추가
2. `dwp-gateway/src/main/java/com/dwp/gateway/config/ApiCallHistoryFilter.java`
   - SSE 요청 감지 로직 추가
   - SSE 요청 요약 기록 정책 적용
3. `dwp-gateway/src/main/resources/application.yml`
   - Gateway 단일 경유 주석 추가
   - 확장 포인트 TODO 주석 추가
4. `docs/AURA_PLATFORM_INTEGRATION_GUIDE.md`
   - Gateway 단일 경유 원칙 추가
5. `README.md`
   - Aura-Platform 엔드포인트 섹션에 Gateway 단일 경유 강조
   - 필수 헤더 명시

---

## 🔍 핵심 변경 사항

### 1. 필수 헤더 검증 강화
```java
// RequiredHeaderFilter: X-Tenant-ID 필수 검증
if (tenantId == null || tenantId.trim().isEmpty()) {
    exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
    return exchange.getResponse().setComplete();
}
```

### 2. SSE 응답 헤더 보장
```java
// SseResponseHeaderFilter: 필수 SSE 헤더 설정
headers.set(CONTENT_TYPE, TEXT_EVENT_STREAM);
headers.set(CACHE_CONTROL, NO_CACHE);
headers.set(CONNECTION, KEEP_ALIVE);
headers.set(X_ACCEL_BUFFERING, NO);
```

### 3. SSE 요청 요약 기록
```java
// ApiCallHistoryFilter: SSE 요청은 요약만 기록
if (isSseRequest) {
    // queryString, requestSizeBytes, responseSizeBytes는 null로 기록
    // path, statusCode, latency, tenantId, userId, agentId만 기록
}
```

---

## 📚 관련 문서

- [Aura Gateway 단일 경유 명세서](./AURA_GATEWAY_SINGLE_POINT_SPEC.md) ⭐
- [Aura Gateway 단일 경유 체크리스트](./AURA_GATEWAY_SINGLE_POINT_CHECKLIST.md)
- [Aura-Platform 통합 가이드](./AURA_PLATFORM_INTEGRATION_GUIDE.md)
- [프론트엔드 API 스펙](./FRONTEND_API_SPEC.md)

---

## ✅ 통과 조건 확인

### Gateway 단일 진입점
- ✅ 프론트엔드는 Gateway(8080)만 호출
- ✅ Gateway가 Aura-Platform(9000)으로 라우팅
- ✅ 직접 접근 금지 정책 문서화

### SSE 스트리밍 품질
- ✅ Gateway가 스트림을 중간에 끊지 않음 (타임아웃 300s)
- ✅ 필수 SSE 헤더 보장 (Content-Type, Cache-Control, Connection, X-Accel-Buffering)
- ✅ POST SSE 지원 (요청 body 전달)

### 헤더 계약 강제
- ✅ X-Tenant-ID 필수 검증 (없으면 400)
- ✅ 표준 헤더 다운스트림 전파 보장
- ✅ CORS에서 모든 표준 헤더 허용

### API Call History 정책
- ✅ SSE 요청은 요약만 기록
- ✅ sys_api_call_histories가 과도하게 증가하지 않음
- ✅ 전체 서비스 공통 호출 이력은 Gateway에서 단일 적재

---

## 🚀 다음 단계 (선택사항)

1. **JWT 검증 확장**: Gateway에서 JWT 검증 후 Aura-Platform으로 전달
2. **모니터링 강화**: SSE 스트림 품질 메트릭 수집
3. **부하 테스트**: 장시간 스트리밍 부하 테스트 수행

---

**작업 완료일**: 2026-01-20  
**작성자**: DWP Backend Team  
**검토 상태**: ✅ 완료
