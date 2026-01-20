# Aura 통신 Gateway 단일 경유 명세서

**작성일**: 2026-01-20  
**버전**: 1.0  
**목적**: Aura-Platform 통신이 Gateway(8080)를 단일 진입점으로 사용하는 것을 명확히 정의

---

## ⚠️ 핵심 원칙: Gateway 단일 경유 필수

**프론트엔드는 절대 Aura-Platform(포트 9000)에 직접 접근하지 않습니다.**

```
✅ 올바른 경로:
프론트엔드 → Gateway(8080) → Aura-Platform(9000)

❌ 금지된 경로:
프론트엔드 → Aura-Platform(9000) 직접 접근
```

**이유**:
1. **통합 모니터링**: 모든 API 호출 이력이 Gateway에서 단일 지점으로 기록됨
2. **헤더 계약 강제**: 필수 헤더(X-Tenant-ID 등) 검증 및 전파 보장
3. **SSE 안정화**: Gateway에서 스트리밍 품질 보장 (타임아웃, 버퍼링 방지)
4. **보안 정책**: 향후 JWT 검증 등 보안 정책을 Gateway에서 일괄 적용 가능
5. **CORS 관리**: Gateway에서 CORS 정책 일괄 관리

---

## 1. Gateway 라우팅 설정

### 라우팅 규칙
- **경로**: `/api/aura/**`
- **대상**: `http://localhost:9000` (로컬 개발) 또는 `${AURA_PLATFORM_URI}` (환경 변수)
- **필터**: `StripPrefix=1` (경로 변환: `/api/aura/**` → `/aura/**`)

### 설정 파일 위치
- `dwp-gateway/src/main/resources/application.yml` (라인 38-54)
- `dwp-gateway/src/main/resources/application-dev.yml` (라인 28-34)
- `dwp-gateway/src/main/resources/application-prod.yml` (라인 39-44)

---

## 2. SSE 스트리밍 계약

### 필수 응답 헤더
Gateway는 SSE 요청에 대해 다음 헤더를 보장합니다:

| 헤더 | 값 | 목적 |
|------|-----|------|
| `Content-Type` | `text/event-stream` | SSE 스트림 식별 |
| `Cache-Control` | `no-cache` | 캐싱 방지 |
| `Connection` | `keep-alive` | 커넥션 유지 |
| `X-Accel-Buffering` | `no` | 프록시 버퍼링 방지 (Nginx 환경 대비) |
| `Transfer-Encoding` | `chunked` | 청크 전송 (자동 설정) |

### 타임아웃 설정
- **Response Timeout**: `300s` (5분)
- **Connect Timeout**: `10s`
- **커넥션 풀**: `max-connections: 500`, `max-idle-time: 30s`

### POST SSE 지원
- 프론트엔드는 `POST /api/aura/test/stream` 요청 가능
- Gateway는 POST 요청 body를 Aura-Platform으로 전달
- SSE 응답을 스트리밍으로 처리 (버퍼링 없음)

---

## 3. 헤더 계약

### 필수 헤더
| 헤더 | 필수 여부 | 설명 | 기본값 |
|------|----------|------|--------|
| `X-Tenant-ID` | ✅ **필수** | 멀티테넌시 식별자 | 없으면 400 Bad Request |
| `X-DWP-Source` | 권장 | 요청 출처 (FRONTEND/AURA/INTERNAL/BATCH) | `FRONTEND` |
| `X-DWP-Caller-Type` | 권장 | 호출자 타입 (USER/AGENT/SYSTEM) | `USER` |

### 전파 헤더
다음 헤더는 Gateway에서 Aura-Platform으로 자동 전파됩니다:
- `Authorization`: JWT 토큰
- `X-Tenant-ID`: 테넌트 ID
- `X-User-ID`: 사용자 ID
- `X-Agent-ID`: 에이전트 세션 ID
- `X-DWP-Source`: 요청 출처
- `X-DWP-Caller-Type`: 호출자 타입
- `Last-Event-ID`: SSE 재연결 지원

### 헤더 검증 필터
- **RequiredHeaderFilter**: `X-Tenant-ID` 필수 검증, 기본값 설정
- **HeaderPropagationFilter**: 헤더 전파 보장 및 로깅

---

## 4. SSE 재연결 지원

### Last-Event-ID 헤더
- 프론트엔드는 재연결 시 `Last-Event-ID` 헤더를 전송
- Gateway는 이 헤더를 Aura-Platform으로 전파
- Aura-Platform이 지원하는 경우, 중단 지점부터 재개 가능

### Event ID 생성
- Gateway의 `SseReconnectionFilter`가 SSE 응답에 `id:` 라인 추가
- 이벤트 ID 형식: 타임스탬프 기반 고유 ID

---

## 5. API 호출 이력 정책

### 일반 요청
- 전체 정보 기록: path, queryString, requestSizeBytes, responseSizeBytes, latency 등

### SSE 요청 (요약 기록)
- **요약 정보만 기록**: path, statusCode, latency, tenantId, userId, agentId
- **제외 항목**: queryString, requestSizeBytes, responseSizeBytes (스트리밍이므로 의미 없음)
- **목적**: 장시간 스트리밍으로 인한 과도한 로그 방지

### 기록 위치
- 테이블: `sys_api_call_histories`
- 수집: Gateway `ApiCallHistoryFilter` → Auth Server `/internal/api-call-history`

---

## 6. 인증 흐름 (현재 및 확장)

### 현재 구조
```
프론트엔드 → Gateway(8080) → Aura-Platform(9000)
           [JWT 전파]      [JWT 수신]
```

- Gateway는 `Authorization` 헤더를 그대로 전파
- Aura-Platform이 JWT 검증을 수행 (또는 Gateway에서 검증 가능)

### 향후 확장 포인트 (TODO)
- Gateway에서 JWT 검증 후 Aura-Platform으로 전달
- Auth Server와 통신하여 JWT 검증
- 검증 실패 시 Gateway에서 401 반환

---

## 7. CORS 설정

### 허용 헤더
- `Authorization`
- `X-Tenant-ID`
- `X-User-ID`
- `X-Agent-ID`
- `X-DWP-Source`
- `X-DWP-Caller-Type`
- `Last-Event-ID`
- `Content-Type`
- `Accept`

### 설정 파일
- `dwp-gateway/src/main/java/com/dwp/gateway/config/CorsConfig.java`

---

## 8. 프론트엔드 구현 가이드

### 올바른 요청 예시
```typescript
// ✅ 올바른 방법: Gateway를 통해 요청
fetch('http://localhost:8080/api/aura/test/stream', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'X-Tenant-ID': tenantId,  // 필수
    'X-User-ID': userId,
    'X-Agent-ID': agentId,
    'X-DWP-Source': 'AURA',
    'X-DWP-Caller-Type': 'AGENT',
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream'
  },
  body: JSON.stringify({
    prompt: '...',
    context: {...}
  })
});
```

### 잘못된 요청 예시
```typescript
// ❌ 금지: Aura-Platform에 직접 접근
fetch('http://localhost:9000/aura/test/stream', {
  // ...
});
```

---

## 9. 테스트 방법

### curl 검증
```bash
# SSE 스트리밍 요청
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "X-Agent-ID: agent_test_123" \
  -H "X-DWP-Source: AURA" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"prompt": "test"}' \
  --no-buffer
```

### 응답 헤더 확인
```bash
# 응답 헤더 확인
curl -I -X POST http://localhost:8080/api/aura/test/stream \
  -H "X-Tenant-ID: 1" \
  -H "Accept: text/event-stream"
```

예상 응답 헤더:
```
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
X-Accel-Buffering: no
Transfer-Encoding: chunked
```

---

## 10. 문제 해결

### X-Tenant-ID 누락 시
- **증상**: 400 Bad Request
- **해결**: 요청 헤더에 `X-Tenant-ID` 추가

### SSE 스트림이 끊기는 경우
- **확인**: Gateway 타임아웃 설정 (300s)
- **확인**: Aura-Platform 응답 헤더 (`Content-Type: text/event-stream`)
- **확인**: 네트워크 프록시 버퍼링 설정

### 헤더가 전파되지 않는 경우
- **확인**: `HeaderPropagationFilter` 로그 확인
- **확인**: CORS 설정에서 헤더 허용 여부 확인

---

## 11. 관련 문서

- [Aura Gateway 단일 경유 체크리스트](./AURA_GATEWAY_SINGLE_POINT_CHECKLIST.md)
- [프론트엔드 API 스펙](./FRONTEND_API_SPEC.md)
- [Aura-Platform 통합 가이드](./AURA_PLATFORM_INTEGRATION_GUIDE.md)

---

**문서 작성일**: 2026-01-20  
**작성자**: DWP Backend Team  
**검토 상태**: ✅ 완료
