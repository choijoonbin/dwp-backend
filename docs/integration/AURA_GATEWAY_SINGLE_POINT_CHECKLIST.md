# Aura 통신 Gateway 단일 경유 체크리스트

**작성일**: 2026-01-20  
**목적**: Aura-Platform 통신이 Gateway(8080)를 단일 진입점으로 사용하는지 확인

---

## ✅ 0) 중복/현황 체크 결과

### Gateway Routing
- ✅ **구현됨**: `/api/aura/**` → `http://localhost:9000` 라우팅 존재
  - 파일: `dwp-gateway/src/main/resources/application.yml` (라인 38-54)
  - 파일: `dwp-gateway/src/main/resources/application-dev.yml` (라인 28-34)
  - 파일: `dwp-gateway/src/main/resources/application-prod.yml` (라인 39-44)
  - 필터: `StripPrefix=1` (경로 변환), `PreserveHostHeader` (호스트 헤더 보존)

### SSE 타임아웃
- ✅ **구현됨**: `response-timeout: 300s` (5분) 설정
  - 파일: `dwp-gateway/src/main/resources/application.yml` (라인 13)
  - 파일: `dwp-gateway/src/main/resources/application-prod.yml` (라인 28)
  - `connect-timeout: 10000` (10초) 설정됨

### Proxy Flush Mode / Response Buffering 방지
- ⚠️ **부분 구현**: Spring Cloud Gateway는 기본적으로 스트리밍을 지원하지만, 명시적 설정 필요
  - 현재: `SseResponseHeaderFilter`에서 `Cache-Control: no-cache` 설정
  - 추가 필요: `X-Accel-Buffering: no` 헤더 추가 (Nginx 프록시 환경 대비)

### CORS Allowed Headers
- ✅ **구현됨**: `Last-Event-ID`, `X-Agent-ID` 포함
  - 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/CorsConfig.java` (라인 35, 73, 76)
  - 명시적 헤더 목록: `Authorization`, `X-Tenant-ID`, `X-User-ID`, `X-Agent-ID`, `X-DWP-Source`, `X-DWP-Caller-Type`, `Last-Event-ID`

### HeaderPropagationFilter
- ✅ **구현됨**: 헤더 전파 필터 존재
  - 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/HeaderPropagationFilter.java`
  - 전파 헤더: `Authorization`, `X-Tenant-ID`, `X-DWP-Source`, `X-DWP-Caller-Type`, `X-User-ID`, `X-Agent-ID`, `Last-Event-ID`
  - Spring Cloud Gateway는 기본적으로 모든 헤더를 전파하지만, 명시적 로깅 제공

### ApiCallHistoryFilter
- ✅ **구현됨**: API 호출 이력 자동 적재
  - 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/ApiCallHistoryFilter.java`
  - 현재 동작: 모든 요청에 대해 이력 기록 (SSE 요청 포함)
  - ⚠️ **개선 필요**: SSE 요청은 요약만 기록하도록 정책 수정 필요 (4번 작업)

---

## 📋 보완 필요 사항

1. **SSE 응답 헤더 강화**: `X-Accel-Buffering: no` 헤더 추가
2. **헤더 Contract 강제**: `X-Tenant-ID` 필수 체크 추가
3. **API Call History 정책**: SSE 요청 요약 기록 정책 적용
4. **문서 업데이트**: Gateway 단일 경유 강조

---

**체크 완료일**: 2026-01-20  
**다음 작업**: 1) Gateway SSE 계약 강화
