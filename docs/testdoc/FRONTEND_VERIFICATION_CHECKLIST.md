# 프론트엔드 확인 요청 체크리스트

> **작성일**: 2026-01-16  
> **대상**: 프론트엔드 개발팀  
> **목적**: 백엔드 통합 전 확인 사항 점검

---

## ✅ 확인 완료 항목

### 1. 포트 충돌 방지

**✅ 확인 완료**: Gateway의 `application.yml`에서 Aura-Platform 라우팅이 `http://localhost:9000`으로 설정되어 있습니다.

**설정 파일 위치**:
- `dwp-gateway/src/main/resources/application.yml`
- `dwp-gateway/src/main/resources/application-prod.yml`
- `dwp-gateway/src/main/resources/application-dev.yml`

**라우팅 설정**:
```yaml
- id: aura-platform
  uri: ${AURA_PLATFORM_URI:http://localhost:9000}  # ✅ 포트 9000 확정
  predicates:
    - Path=/api/aura/**
  filters:
    - StripPrefix=1
```

**확인 방법**:
```bash
# Gateway 설정 확인
grep -r "localhost:9000" dwp-gateway/src/main/resources/
```

---

### 2. 사용자 식별자 일관성

#### 2.1 JWT 토큰의 사용자 식별자 필드명

**✅ 확인 완료**: JWT 토큰의 사용자 식별자는 **`sub`** 필드를 사용합니다.

**구현 위치**:
- `dwp-main-service/src/main/java/com/dwp/services/main/util/JwtTokenValidator.java`

**코드 확인**:
```java
public String extractUserId(String token) {
    Claims claims = validateToken(token);
    return claims.getSubject();  // ✅ JWT의 sub 클레임 사용
}
```

**JWT Payload 구조**:
```json
{
  "sub": "backend_user_001",        // ✅ 사용자 ID (필수)
  "tenant_id": "tenant1",            // 테넌트 ID (필수)
  "email": "user@dwp.com",
  "role": "user",
  "exp": 1706156400,
  "iat": 1706152860
}
```

**프론트엔드 구현 가이드**:
```javascript
// JWT에서 사용자 ID 추출
const token = localStorage.getItem('jwt_token');
const payload = JSON.parse(atob(token.split('.')[1]));
const userId = payload.sub;  // ✅ JWT의 sub 클레임 사용

// API 요청 시 헤더에 포함
headers: {
  'Authorization': `Bearer ${token}`,
  'X-Tenant-ID': payload.tenant_id,
  'X-User-ID': userId  // ✅ JWT의 sub 값과 일치
}
```

#### 2.2 X-User-ID 헤더 처리

**✅ 확인 완료**: Gateway와 Main Service가 `X-User-ID` 헤더를 올바르게 처리합니다.

**Gateway 처리**:
- `HeaderPropagationFilter`: `X-User-ID` 헤더를 Aura-Platform으로 전파
- 위치: `dwp-gateway/src/main/java/com/dwp/gateway/config/HeaderPropagationFilter.java`

**Main Service 처리**:
- `HitlSecurityInterceptor`: JWT의 `sub`와 `X-User-ID` 헤더 일치 확인
- 위치: `dwp-main-service/src/main/java/com/dwp/services/main/config/HitlSecurityInterceptor.java`

**검증 로직**:
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

---

### 3. SSE 전송 방식

**✅ 확인 완료**: POST `/api/aura/test/stream` 요청에 대한 SSE 응답이 정상 동작합니다.

**구현 내용**:
1. **RequestBodyLoggingFilter**: POST 요청 body 로깅 및 전달 보장
2. **SseResponseHeaderFilter**: POST 요청에 대한 SSE 응답 헤더 보장
3. **Spring Cloud Gateway**: 기본적으로 POST 요청의 SSE 응답을 지원

**테스트 방법**:
```bash
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -d '{"prompt": "test", "context": {"url": "http://localhost:4200/mail"}}'
```

**예상 응답**:
```
Content-Type: text/event-stream
Cache-Control: no-cache
Transfer-Encoding: chunked

data: {"type":"thought","content":"..."}

data: {"type":"plan_step","content":"..."}
```

**로그 확인**:
```bash
# Gateway 로그에서 body 전달 확인
tail -f /tmp/dwp-gateway.log | grep "POST request body"
```

---

### 4. 추가 확인

#### 4.1 SSE 재연결 지원 (id: 라인)

**✅ 구현 완료**: SSE 응답에 `id:` 라인을 포함하여 재연결을 지원합니다.

**구현 내용**:
- **SseReconnectionFilter**: SSE 응답에 자동으로 `id:` 라인 추가
- **Last-Event-ID 헤더 전파**: 클라이언트의 `Last-Event-ID` 헤더를 Aura-Platform으로 전달

**SSE 이벤트 형식**:
```
id: 1706156400123
data: {"type":"thought","content":"..."}

id: 1706156400124
data: {"type":"plan_step","content":"..."}
```

**재연결 흐름**:
1. 클라이언트가 연결 끊김
2. 클라이언트가 `Last-Event-ID: 1706156400123` 헤더와 함께 재연결
3. Gateway가 `Last-Event-ID` 헤더를 Aura-Platform으로 전달
4. Aura-Platform이 해당 ID 이후의 이벤트부터 재개

**프론트엔드 구현 예시**:
```javascript
const eventSource = new EventSource('/api/aura/test/stream', {
  headers: {
    'Authorization': `Bearer ${token}`,
    'X-Tenant-ID': tenantId,
    'Last-Event-ID': lastEventId  // 재연결 시 마지막 이벤트 ID
  }
});

eventSource.addEventListener('message', (event) => {
  const eventId = event.lastEventId;  // 이벤트 ID 저장
  // ... 이벤트 처리
});
```

#### 4.2 CORS 설정

**✅ 확인 완료**: CORS 설정에 필수 헤더가 포함되어 있습니다.

**설정 위치**:
- `dwp-gateway/src/main/java/com/dwp/gateway/config/CorsConfig.java`

**현재 설정**:
```java
allowedHeaders = "*"  // ✅ 모든 헤더 허용
allowedMethods = "GET,POST,PUT,DELETE,PATCH,OPTIONS"
allowCredentials = true
```

**허용되는 헤더**:
- `Authorization`
- `X-Tenant-ID`
- `X-User-ID`
- `X-DWP-Source`
- `X-DWP-Caller-Type`
- `Last-Event-ID` (SSE 재연결)
- `Content-Type`
- `Accept`

**환경 변수 설정**:
```bash
# 허용할 Origin 설정
export CORS_ALLOWED_ORIGINS=http://localhost:4200,http://localhost:3000

# 허용할 메서드 설정 (기본값 사용 가능)
export CORS_ALLOWED_METHODS=GET,POST,PUT,DELETE,PATCH,OPTIONS

# 허용할 헤더 설정 (기본값: * - 모든 헤더 허용)
export CORS_ALLOWED_HEADERS=*
```

---

## 📋 확인 체크리스트

### 백엔드 팀 확인 사항

- [x] **포트 충돌 방지**: Gateway의 application.yml에서 Aura-Platform 라우팅이 `http://localhost:9000`으로 설정되어 있는지 확인
- [x] **JWT 사용자 식별자**: JWT 토큰의 사용자 식별자 필드명 확인 (`sub` 사용)
- [x] **X-User-ID 헤더 처리**: Gateway와 Main Service가 `X-User-ID` 헤더를 올바르게 처리하는지 확인
- [x] **POST SSE 응답**: POST `/api/aura/test/stream` 요청에 대한 SSE 응답이 정상 동작하는지 테스트
- [x] **SSE 재연결 지원**: SSE 응답에 `id:` 라인 포함 및 `Last-Event-ID` 헤더 처리
- [x] **CORS 설정**: 필수 헤더 포함 확인

### 프론트엔드 팀 확인 사항

- [x] **JWT sub 필드 사용**: JWT의 `sub` 클레임을 `X-User-ID` 헤더로 전달 ✅ (구현 완료)
- [x] **POST 요청 구현**: POST `/api/aura/test/stream` 요청 구현 완료 ✅
- [x] **SSE 재연결 구현**: `Last-Event-ID` 헤더를 사용한 재연결 로직 구현 ✅
- [x] **CORS 헤더 포함**: 필요한 모든 헤더가 요청에 포함됨 ✅
- [x] **에러 처리**: 다양한 에러 상황에 대한 처리 구현 ✅

### 통합 테스트 필요 항목

- [ ] **실제 백엔드 연결 테스트**: Gateway(8080)를 통한 Aura-Platform(9000) 연결 테스트
- [ ] **Context 데이터 크기 확인**: 256KB 이하인지 확인
- [ ] **HITL API 통합 테스트**: 승인/거절 API 호출 및 스트림 재개 확인

---

## 🔧 테스트 시나리오

### 1. 기본 SSE 연결 테스트

```bash
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: {USER_ID}" \
  -d '{"prompt": "test", "context": {}}'
```

### 2. SSE 재연결 테스트

```bash
# 첫 번째 연결
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Accept: text/event-stream" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -d '{"prompt": "test", "context": {}}'

# 재연결 (Last-Event-ID 포함)
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Accept: text/event-stream" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "Last-Event-ID: 1706156400123" \
  -d '{"prompt": "test", "context": {}}'
```

### 3. CORS Preflight 테스트

```bash
curl -X OPTIONS http://localhost:8080/api/aura/test/stream \
  -H "Origin: http://localhost:4200" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: Authorization,X-Tenant-ID,X-User-ID,Content-Type" \
  -v
```

---

## 📞 문의 사항

확인 과정에서 문제가 발생하면 다음을 확인하세요:

1. **포트 충돌**: `lsof -i :9000`으로 포트 사용 확인
2. **JWT 검증**: `JwtTokenValidator` 로그 확인
3. **SSE 응답**: Gateway 로그에서 `SseResponseHeaderFilter` 실행 확인
4. **CORS 오류**: 브라우저 콘솔에서 CORS 오류 메시지 확인

---

**최종 업데이트**: 2026-01-16  
**담당자**: DWP Backend Team
