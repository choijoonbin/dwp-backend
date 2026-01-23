# BE P1-X.1: Aura SSE 운영 안정화 작업 요약

**작성일**: 2026-01-20  
**목적**: Gateway 스트림 정책 강화, 로깅 폭발 방지, 추적성 강화

---

## ✅ 완료 사항

### 1) SSE 요청 요약 로그 정책 강화 (로깅 폭발 방지)

**문제**: SSE 요청이 chunk 단위로 반복 기록되면 DB가 터질 수 있음

**해결**:
- `ApiCallHistoryFilter`에서 SSE 요청 식별 강화
  - 요청 Accept 헤더 확인
  - 경로에 `/stream` 포함 여부 확인
  - 응답 Content-Type 확인 (`text/event-stream`)
- SSE 요청은 1회 요청에 대해 요약 1건만 기록
  - 기록 항목: path, statusCode, latencyMs, tenantId, userId, agentId, traceId, source, errorCode
  - 제외 항목: queryString, requestSizeBytes, responseSizeBytes
- 비정상 종료 시 errorCode 기록 (499: CLIENT_CLOSED, 504: GATEWAY_TIMEOUT 등)

**파일**:
- `dwp-gateway/src/main/java/com/dwp/gateway/config/ApiCallHistoryFilter.java`

---

### 2) Gateway 헤더/CORS 강화

**확인 사항**:
- ✅ CORS 설정에 `Last-Event-ID`, `X-Agent-ID` 포함됨
- ✅ `HeaderPropagationFilter`가 필수 헤더 전파 보장
- ✅ `RequiredHeaderFilter`가 `X-Tenant-ID` 필수 검증

**파일**:
- `dwp-gateway/src/main/java/com/dwp/gateway/config/CorsConfig.java`
- `dwp-gateway/src/main/java/com/dwp/gateway/config/HeaderPropagationFilter.java`
- `dwp-gateway/src/main/java/com/dwp/gateway/config/RequiredHeaderFilter.java`

---

### 3) Aura 스트림 전용 Observability 강화

**구현**:
- traceId 생성 및 Reactive Context에 설정
- 로그 메시지에 컨텍스트 정보 포함: `[traceId=xxx, tenantId=xxx, userId=xxx, agentId=xxx, path=xxx]`
- Reactive 환경에서는 MDC 대신 Context 사용 (Thread-local 제한)

**파일**:
- `dwp-gateway/src/main/java/com/dwp/gateway/config/ApiCallHistoryFilter.java`

---

### 4) Admin Monitoring 연계 포인트 정리

**문서화**:
- `ADMIN_MONITORING_API_SPEC.md`에 SSE 요청 정책 명시
- `/api/admin/monitoring/api-histories` API에 데이터 소스 및 수집 방식 설명 추가
- 향후 확장 포인트 (service_name 필드) TODO 명시

**파일**:
- `docs/ADMIN_MONITORING_API_SPEC.md`

---

### 5) 테스트 작성

**구현**:
- `ApiCallHistoryFilterTest` 작성
  - 일반 API 요청은 전체 정보 기록 검증
  - SSE 요청은 요약 1건만 기록 검증
  - SSE 요청 경로 식별 검증
  - SSE 요청 비정상 종료 시 errorCode 기록 검증

**파일**:
- `dwp-gateway/src/test/java/com/dwp/gateway/config/ApiCallHistoryFilterTest.java`

---

### 6) 문서 업데이트

**업데이트 내용**:
- `ADMIN_MONITORING_API_SPEC.md` 상단에 핵심 정책 5줄 추가:
  1. Aura 통신은 Gateway 경유 필수
  2. SSE 요청 요약 기록 (로깅 폭발 방지)
  3. 필수 헤더 CORS 허용
  4. traceId 추적성
  5. Gateway 단일 진입점

**파일**:
- `docs/ADMIN_MONITORING_API_SPEC.md`
- `docs/BE_P1-X1_STATUS_CHECK.md` (현황 점검 결과)
- `docs/BE_P1-X1_AURA_SSE_OPERATIONAL_STABILIZATION_SUMMARY.md` (본 문서)

---

## 📋 검증 방법

### 수동 검증
1. 프론트에서 AI 스트리밍 호출
2. `sys_api_call_histories` 테이블 확인
3. 스트리밍 1회에 1건만 쌓이는지 확인
4. SSE 요청은 queryString, requestSizeBytes, responseSizeBytes가 null인지 확인

### 자동 테스트
```bash
./gradlew :dwp-gateway:test --tests "ApiCallHistoryFilterTest"
```

---

## 🔍 주요 변경 사항

### ApiCallHistoryFilter.java
- SSE 요청 식별 로직 강화 (응답 Content-Type 확인 추가)
- Reactive Context에 traceId, tenantId, userId, agentId 설정
- 로그 메시지에 컨텍스트 정보 포함
- errorCode 자동 추출 (499, 504 등)

### ADMIN_MONITORING_API_SPEC.md
- 상단에 핵심 정책 5줄 추가
- API 호출 이력 조회 섹션에 SSE 요청 정책 명시
- 확장 포인트 TODO 추가

---

## ✅ 통과 조건

- ✅ SSE 요청이 1회에 1건만 기록됨
- ✅ 일반 요청은 기존대로 전체 정보 기록됨
- ✅ traceId가 모든 요청에 기록됨
- ✅ 로그에 컨텍스트 정보 포함됨
- ✅ 테스트 통과
- ✅ 문서 업데이트 완료

---

**작업 완료일**: 2026-01-20  
**작성자**: DWP Backend Team
