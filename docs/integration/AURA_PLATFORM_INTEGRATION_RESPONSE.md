# Aura-Platform 통합 체크리스트 응답

> **작성일**: 2026-01-16  
> **대상**: Aura-Platform 개발팀  
> **목적**: Aura-Platform의 통합 체크리스트에 대한 백엔드 확인 및 응답

---

## ✅ 백엔드 확인 완료 사항

### 1. 포트 충돌 방지

**✅ 확인 완료**: Gateway의 `application.yml`에서 Aura-Platform 라우팅이 `http://localhost:9000`으로 설정되어 있습니다.

**설정 파일 위치**:
- `dwp-gateway/src/main/resources/application.yml` (32번 라인)
- `dwp-gateway/src/main/resources/application-prod.yml` (40번 라인)
- `dwp-gateway/src/main/resources/application-dev.yml` (28번 라인)

**라우팅 설정**:
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: aura-platform
          uri: ${AURA_PLATFORM_URI:http://localhost:9000}  # ✅ 포트 9000 확정
          predicates:
            - Path=/api/aura/**
          filters:
            - StripPrefix=1  # /api/aura/** → /aura/**로 변환
```

**확인 방법**:
```bash
# Gateway 설정 확인
grep -r "localhost:9000" dwp-gateway/src/main/resources/
```

**✅ 상태**: 모든 설정 파일에서 포트 9000으로 올바르게 설정됨

---

### 2. 사용자 식별자(User-ID) 일관성

#### 2.1 JWT 토큰 구조

**✅ 확인 완료**: 백엔드는 JWT의 `sub` 클레임을 사용자 식별자로 사용합니다.

**백엔드 구현** (`dwp-main-service/src/main/java/com/dwp/services/main/util/JwtTokenValidator.java`):
```java
public String extractUserId(String token) {
    Claims claims = validateToken(token);
    return claims.getSubject();  // ✅ JWT의 sub 클레임 사용
}
```

**JWT Payload 구조** (백엔드 기대 형식):
```json
{
  "sub": "user123",           // ✅ 사용자 ID (필수)
  "tenant_id": "tenant1",     // ✅ 테넌트 ID (필수)
  "email": "user@dwp.com",    // 선택
  "role": "user",             // 선택
  "exp": 1706152860,          // Unix timestamp (초 단위)
  "iat": 1706149260           // Unix timestamp (초 단위)
}
```

**✅ 상태**: Aura-Platform과 백엔드 모두 JWT의 `sub` 클레임을 사용하므로 일관성 유지됨

#### 2.2 X-User-ID 헤더 처리

**✅ 확인 완료**: 백엔드는 `X-User-ID` 헤더를 처리하고 검증합니다.

**백엔드 구현**:
1. **Gateway**: `HeaderPropagationFilter`에서 `X-User-ID` 헤더를 Aura-Platform으로 전파
2. **Main Service**: `HitlSecurityInterceptor`에서 JWT의 `sub`와 `X-User-ID` 헤더 일치 확인

**검증 로직** (`HitlSecurityInterceptor.java`):
```java
// JWT에서 사용자 ID 추출
String jwtUserId = jwtTokenValidator.extractUserId(token);  // sub 클레임

// 헤더의 X-User-ID와 비교
String headerUserId = request.getHeader("X-User-ID");

// 일치 여부 확인
if (!jwtUserId.equals(headerUserId)) {
    throw new BaseException(ErrorCode.FORBIDDEN, "User ID mismatch");
}
```

**⚠️ 중요**: 
- HITL API 호출 시 `X-User-ID` 헤더 값이 JWT의 `sub`와 **반드시 일치**해야 합니다
- 불일치 시 `403 Forbidden` 오류가 발생합니다

**✅ 상태**: 백엔드에서 JWT `sub`와 `X-User-ID` 일치를 강제하므로 일관성 보장됨

---

### 3. SSE 전송 방식 (POST)

#### 3.1 Gateway 라우팅

**✅ 확인 완료**: Gateway가 POST 요청을 Aura-Platform으로 정상 전달합니다.

**구현 내용**:
- `RequestBodyLoggingFilter`: POST 요청 body 로깅 및 전달 보장
- `SseResponseHeaderFilter`: POST 요청에 대한 SSE 응답 헤더 보장
- Spring Cloud Gateway 기본 동작으로 POST SSE 응답 지원

**테스트 결과**:
```bash
# Gateway를 통한 POST 요청 테스트
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -d '{"prompt": "test", "context": {"url": "http://localhost:4200/mail"}}'
```

**✅ 상태**: POST 요청 라우팅 정상 작동 확인됨

#### 3.2 Gateway 타임아웃 설정

**✅ 확인 완료**: Gateway의 SSE 연결 타임아웃이 300초로 설정되어 있습니다.

**설정 위치**: `dwp-gateway/src/main/resources/application.yml`

**타임아웃 설정**:
```yaml
spring:
  cloud:
    gateway:
      httpclient:
        response-timeout: 300s  # ✅ 5분 (300초) - Aura-Platform HITL 대기 타임아웃과 일치
        connect-timeout: 10000  # 10초
        pool:
          max-connections: 500
          max-idle-time: 30s
```

**✅ 상태**: Aura-Platform의 HITL 대기 타임아웃(300초)과 일치하므로 문제없음

#### 3.3 요청 본문 크기 제한

**⚠️ 확인 필요**: Gateway의 요청 본문 크기 제한 확인

**현재 상태**:
- Spring Cloud Gateway 기본값: **256KB** (Netty 기본 설정)
- FastAPI 기본값: **1MB**

**권장 설정**:
```yaml
spring:
  cloud:
    gateway:
      httpclient:
        # 요청 본문 크기 제한 (필요 시 설정)
        # 기본값: 256KB (Netty)
        # 큰 context 데이터를 위해 증가 필요할 수 있음
```

**⚠️ 주의사항**:
- `context` 객체가 큰 경우(256KB 이상) Gateway에서 요청이 거부될 수 있음
- 필요 시 Gateway의 요청 본문 크기 제한을 증가시켜야 함

**확인 방법**:
```bash
# 큰 context 데이터로 테스트
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Content-Type: application/json" \
  -d '{"prompt": "test", "context": {...큰 데이터...}}'
```

**✅ 권장 조치**: 
- 일반적인 `context` 데이터는 256KB 이하로 유지
- 큰 데이터가 필요한 경우 Gateway 설정 조정 필요

#### 3.4 스트리밍 응답 버퍼링

**✅ 확인 완료**: Gateway가 POST 요청에 대한 SSE 응답을 버퍼링하지 않습니다.

**구현 내용**:
- `SseResponseHeaderFilter`: SSE 응답 헤더 보장 (`Content-Type: text/event-stream`, `Cache-Control: no-cache`)
- Spring Cloud Gateway는 기본적으로 스트리밍 응답을 버퍼링하지 않음
- `Transfer-Encoding: chunked` 자동 설정

**Aura-Platform 헤더 전파**:
- Aura-Platform이 설정한 `X-Accel-Buffering: no` 헤더는 Gateway를 통해 프론트엔드로 전달됨
- Gateway는 이 헤더를 수정하지 않음

**✅ 상태**: 스트리밍 응답이 버퍼링되지 않도록 보장됨

---

### 4. 추가 확인 사항

#### 4.1 SSE 이벤트 ID 포함

**✅ 구현 완료**: Gateway의 `SseReconnectionFilter`가 SSE 응답에 `id:` 라인을 자동으로 추가합니다.

**구현 내용**:
- `SseReconnectionFilter`: SSE 응답에 `id:` 라인 자동 추가
- `Last-Event-ID` 헤더를 Aura-Platform으로 전달

**동작 방식**:
1. Aura-Platform이 `id:` 라인을 포함하지 않은 경우, Gateway가 자동으로 추가
2. Aura-Platform이 이미 `id:` 라인을 포함한 경우, 그대로 전달

**✅ 상태**: SSE 재연결 지원 완료

#### 4.2 Last-Event-ID 헤더 전파

**✅ 구현 완료**: Gateway가 `Last-Event-ID` 헤더를 Aura-Platform으로 전달합니다.

**구현 내용**:
- `HeaderPropagationFilter`: `Last-Event-ID` 헤더 전파 추가
- Gateway는 모든 헤더를 기본적으로 전파하지만, 명시적으로 로깅하여 확인 가능

**✅ 상태**: `Last-Event-ID` 헤더 전파 보장됨

---

## ⚠️ Aura-Platform에서 확인 필요 사항

### 1. X-User-ID 헤더 처리 (선택사항)

**현재 상태**:
- Aura-Platform은 JWT의 `sub` 클레임만 사용
- `X-User-ID` 헤더는 전달되지만 사용하지 않음

**권장 사항**:
- ✅ 현재 구현 유지 (JWT `sub` 사용) - 보안상 더 안전
- ⚠️ 선택적으로 `X-User-ID` 헤더를 우선 사용하도록 구현 가능 (백엔드와 일치 확인 후)

**백엔드 요구사항**:
- HITL API 호출 시 `X-User-ID` 헤더 값이 JWT의 `sub`와 일치해야 함
- Aura-Platform에서도 동일한 검증 로직 적용 권장

---

### 2. Gateway 타임아웃 문서화

**✅ 문서화 완료**: Gateway 타임아웃 설정이 문서에 명시되어 있습니다.

**문서 위치**:
- `docs/AURA_PLATFORM_INTEGRATION_GUIDE.md`
- `docs/AURA_PLATFORM_VERIFICATION_REQUIREMENTS.md`

**타임아웃 설정**:
- **SSE 연결 타임아웃**: 300초 (5분)
- **연결 타임아웃**: 10초
- **커넥션 풀**: max-connections: 500, max-idle-time: 30s

**✅ 상태**: 문서화 완료

---

### 3. 요청 본문 크기 제한

**✅ 확인 완료**: Gateway의 요청 본문 크기 제한은 **256KB** (기본값)입니다.

**현재 제한**:
- **Spring Cloud Gateway (Netty)**: **256KB** (262,144 bytes) - 기본값
- **FastAPI**: **1MB** (기본값, 설정 가능)

**⚠️ 중요**: 
- `context` 객체가 256KB를 초과하는 경우 Gateway에서 요청이 거부될 수 있습니다
- FastAPI는 1MB까지 허용하지만, Gateway를 통과하지 못할 수 있습니다

**권장 조치**:
1. **일반적인 사용**: `context` 데이터를 **256KB 이하로 유지** (권장)
   - 필요한 데이터만 선별하여 전송
   - 불필요한 메타데이터 제거
   - 중첩된 객체 구조 최적화
2. **큰 데이터 필요 시**: 
   - 백엔드 팀과 별도 논의 후 Gateway 설정 조정
   - 또는 `context` 데이터를 압축하여 전송
   - 또는 필요한 데이터만 선별하여 전송

**Gateway 설정 조정 방법** (필요 시):
- Netty의 요청 본문 크기 제한 변경은 복잡할 수 있음
- 커스텀 `HttpServerInitializer` 구현이 필요할 수 있음
- 일반적으로는 `context` 데이터 최적화를 권장

**✅ 권장**: 
- 현재는 기본값(256KB) 유지
- `context` 데이터를 최적화하여 256KB 이하로 유지
- 큰 데이터가 반드시 필요한 경우, 백엔드 팀과 별도 논의 필요

---

## 📋 최종 확인 체크리스트

### 백엔드 확인 완료 사항

- [x] **포트 9000 라우팅**: Gateway의 `application.yml`에서 `http://localhost:9000` 설정 확인
- [x] **POST 요청 라우팅**: POST `/api/aura/test/stream` 요청이 정상 작동하는지 확인
- [x] **SSE 타임아웃**: 300초로 설정되어 Aura-Platform HITL 타임아웃과 일치
- [x] **X-User-ID 헤더**: JWT `sub`와 일치 검증 구현 완료
- [x] **스트리밍 응답**: 버퍼링되지 않도록 보장됨
- [x] **Last-Event-ID 헤더**: Aura-Platform으로 전파됨
- [x] **SSE 이벤트 ID**: `SseReconnectionFilter`로 자동 추가

### Aura-Platform 확인 필요 사항

- [ ] **요청 본문 크기**: `context` 데이터가 256KB 이하인지 확인
  - ⚠️ Gateway 기본 제한: 256KB
  - 권장: `context` 데이터를 최적화하여 256KB 이하로 유지
  - 큰 데이터가 필요한 경우 백엔드 팀과 별도 논의 필요
- [ ] **X-User-ID 헤더**: 선택적으로 처리하도록 구현 (현재는 JWT `sub`만 사용해도 무방)
  - 백엔드에서 JWT `sub`와 `X-User-ID` 일치를 검증하므로, Aura-Platform에서도 동일한 검증 적용 권장
- [ ] **SSE 이벤트 ID**: Aura-Platform에서 `id:` 라인을 포함하는지 확인
  - Gateway의 `SseReconnectionFilter`가 자동으로 추가하지만, Aura-Platform에서 직접 포함하는 것이 권장됨
  - 재연결 시 `Last-Event-ID` 헤더를 올바르게 처리하는지 확인

---

## 🔧 테스트 시나리오

### 시나리오 1: 기본 POST SSE 연결
```bash
# Gateway를 통한 접근
curl -N -X POST http://localhost:8080/api/aura/test/stream \
  -H "Authorization: Bearer {TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "prompt": "테스트",
    "context": {"url": "http://localhost:4200/mail"}
  }'
```

### 시나리오 2: 재연결 테스트
```bash
# Last-Event-ID 헤더와 함께 재연결
curl -N -X POST http://localhost:8080/api/aura/test/stream \
  -H "Authorization: Bearer {TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "Last-Event-ID: 1706156400123" \
  -d '{
    "prompt": "테스트",
    "context": {"url": "http://localhost:4200/mail"}
  }'
```

### 시나리오 3: 큰 context 데이터 테스트
```bash
# 큰 context 데이터로 테스트 (256KB 제한 확인)
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "prompt": "테스트",
    "context": {...큰 데이터...}
  }'
```

---

## 📞 문의 사항

통합 과정에서 문제가 발생하면 다음을 확인하세요:

1. **포트 충돌**: `lsof -i :9000`으로 포트 사용 확인
2. **Gateway 로그**: `/tmp/dwp-gateway.log`에서 라우팅 및 헤더 전파 확인
3. **요청 본문 크기**: 256KB 제한 초과 시 Gateway 설정 조정 필요
4. **SSE 연결**: 타임아웃(300초) 내에 응답이 완료되는지 확인

---

**최종 업데이트**: 2026-01-16  
**담당자**: DWP Backend Team
