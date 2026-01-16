# 통/협업 체크리스트

> **대상**: 프론트엔드 개발팀, Aura-Platform 개발팀, 백엔드 개발팀  
> **최종 업데이트**: 2026-01-16  
> **목적**: 프로젝트 간 통합 시 충돌 방지 및 일관성 보장

---

## ✅ 체크리스트 항목

### 1. 포트 충돌 방지

#### 확인 사항
- [x] Aura-Platform 포트: **9000** (변경 완료)
- [x] Auth Server 포트: **8001** (변경 완료)
- [x] Gateway 라우팅 설정 확인

#### 포트 구성표

| 서비스 | 포트 | 환경 변수 | 확인 상태 |
|--------|------|-----------|----------|
| Gateway | 8080 | - | ✅ |
| Auth Server | 8001 | - | ✅ |
| Aura-Platform | 9000 | `AURA_PLATFORM_URI` | ✅ |
| Main Service | 8081 | - | ✅ |

#### Gateway 라우팅 설정

**로컬 개발 환경** (`application.yml`):
```yaml
- id: aura-platform
  uri: ${AURA_PLATFORM_URI:http://localhost:9000}  # ✅ 포트 9000
```

**운영 환경** (`application-prod.yml`):
```yaml
- id: aura-platform
  uri: ${AURA_PLATFORM_URI:http://aura-platform:9000}  # ✅ 포트 9000
```

**✅ 확인 완료**: 모든 설정 파일에서 포트 9000으로 올바르게 설정됨

---

### 2. 사용자 식별자(User-ID) 일관성

#### 문제점
프론트엔드는 JWT에서 `sub` 또는 `userId`를 추출하고, 백엔드는 `X-User-ID` 헤더를 기대합니다. 이 두 값이 일치해야 합니다.

#### JWT 구조

**JWT Payload**:
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

#### 사용자 식별자 추출 방법

**프론트엔드 (권장)**:
```javascript
// JWT 토큰 디코딩
const token = localStorage.getItem('jwt_token');
const payload = JSON.parse(atob(token.split('.')[1]));

// 사용자 ID 추출
const userId = payload.sub;  // ✅ JWT의 sub 클레임 사용

// API 요청 시 헤더에 포함
headers: {
  'Authorization': `Bearer ${token}`,
  'X-Tenant-ID': payload.tenant_id,
  'X-User-ID': userId  // ✅ JWT의 sub 값을 그대로 사용
}
```

**백엔드 검증** (`HitlSecurityInterceptor`):
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

#### 일관성 규칙

| 항목 | 값 | 출처 |
|------|-----|------|
| JWT 사용자 ID | `sub` 클레임 | JWT Payload |
| 헤더 사용자 ID | `X-User-ID` | HTTP Header |
| **일치 조건** | `JWT.sub == X-User-ID` | **필수** |

#### ✅ 확인 사항

- [x] JWT의 `sub` 클레임이 사용자 ID로 사용됨
- [x] `X-User-ID` 헤더가 JWT의 `sub`와 일치해야 함
- [x] `HitlSecurityInterceptor`에서 일치 여부 검증 구현됨
- [ ] 프론트엔드에서 JWT의 `sub`를 `X-User-ID`로 전달하는지 확인 필요

#### 프론트엔드 구현 가이드

**✅ 올바른 구현**:
```javascript
// JWT에서 sub 추출
const userId = jwtPayload.sub;

// API 요청 시 헤더에 포함
fetch('/api/aura/hitl/approve/123', {
  headers: {
    'Authorization': `Bearer ${token}`,
    'X-Tenant-ID': tenantId,
    'X-User-ID': userId  // ✅ JWT의 sub 값
  }
});
```

**❌ 잘못된 구현**:
```javascript
// userId를 별도로 관리하는 경우
const userId = userService.getCurrentUserId();  // ❌ JWT와 불일치 가능

// 또는 JWT의 다른 필드 사용
const userId = jwtPayload.userId;  // ❌ sub가 아닌 다른 필드 사용
```

---

### 3. SSE 전송 방식 (POST 지원)

#### 문제점
일반적으로 SSE는 GET 요청이 표준이지만, 프론트엔드는 context 데이터가 커서 POST 방식을 사용합니다.

#### 현재 구현 상태

**프론트엔드 요청**:
```javascript
POST /api/aura/test/stream
Content-Type: application/json
Accept: text/event-stream  // ✅ SSE 응답 요청

{
  "prompt": "사용자 질문",
  "context": { ... }  // 큰 데이터 포함
}
```

**백엔드 Gateway 지원**:
- ✅ Spring Cloud Gateway는 POST 요청에 대한 SSE 응답을 지원합니다
- ✅ `SseResponseHeaderFilter`가 POST 요청도 감지하도록 개선됨
- ✅ `/stream` 경로를 포함한 POST 요청을 SSE로 처리

#### Gateway 필터 개선

**`SseResponseHeaderFilter`**:
```java
// SSE 요청 감지 로직
boolean hasAcceptHeader = acceptHeader != null && 
                         acceptHeader.contains("text/event-stream");
boolean isStreamPath = path != null && path.contains("/stream");
boolean isSseRequest = hasAcceptHeader || isStreamPath;  // ✅ POST도 지원
```

#### 테스트 시나리오

**✅ 성공 케이스**:
```bash
# POST 요청으로 SSE 스트리밍
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -d '{"prompt": "test", "context": {}}'

# 응답 헤더 확인
Content-Type: text/event-stream  # ✅
Cache-Control: no-cache          # ✅
Transfer-Encoding: chunked       # ✅
```

**⚠️ 주의사항**:
- POST 요청 시 `Accept: text/event-stream` 헤더를 명시적으로 포함해야 합니다
- 또는 `/stream` 경로를 포함하면 자동으로 SSE로 처리됩니다
- `Content-Type: application/json`과 함께 사용 가능합니다

#### Aura-Platform 요구사항

**Aura-Platform에서 확인할 사항**:
- [ ] POST `/aura/test/stream` 엔드포인트 구현
- [ ] 요청 본문에서 `prompt`와 `context` 파싱
- [ ] `Content-Type: text/event-stream` 응답 헤더 설정
- [ ] `Cache-Control: no-cache` 응답 헤더 설정
- [ ] 스트리밍 응답 (`Transfer-Encoding: chunked`)

#### ✅ 확인 완료

- [x] Gateway가 POST 요청에 대한 SSE 응답을 지원함
- [x] `SseResponseHeaderFilter`가 POST 요청도 감지하도록 개선됨
- [x] 프론트엔드 요구사항 (POST 방식) 반영됨
- [ ] Aura-Platform에서 POST 엔드포인트 구현 확인 필요

---

## 📋 통합 테스트 체크리스트

### 프론트엔드 → Gateway → Aura-Platform

- [ ] POST `/api/aura/test/stream` 요청 시 SSE 응답 수신 확인
- [ ] `X-User-ID` 헤더가 JWT의 `sub`와 일치하는지 확인
- [ ] `X-Tenant-ID` 헤더 전파 확인
- [ ] `Authorization` 헤더 전파 확인
- [ ] 응답 헤더 (`Content-Type: text/event-stream`) 확인

### 프론트엔드 → Gateway → Main Service (HITL)

- [ ] POST `/api/aura/hitl/approve/{requestId}` 요청 시
  - [ ] JWT의 `sub`와 `X-User-ID` 일치 확인
  - [ ] JWT의 `tenant_id`와 `X-Tenant-ID` 일치 확인
  - [ ] 승인 신호가 Redis Pub/Sub으로 전송되는지 확인

### Aura-Platform → Redis (HITL 신호 수신)

- [ ] Redis Pub/Sub 채널 `hitl:channel:{sessionId}` 구독 확인
- [ ] 승인/거절 신호 수신 확인
- [ ] Unix timestamp (초 단위) 형식 확인

---

## 🔧 문제 해결 가이드

### 포트 충돌 발생 시

**증상**: 서비스가 시작되지 않거나 연결 실패

**해결 방법**:
1. `application.yml`에서 포트 확인
2. 환경 변수 `AURA_PLATFORM_URI` 확인
3. 다른 프로세스가 포트를 사용 중인지 확인: `lsof -i :9000`

### 사용자 ID 불일치 오류

**증상**: `User ID mismatch between JWT and header` 오류

**해결 방법**:
1. 프론트엔드에서 JWT의 `sub` 클레임 확인
2. `X-User-ID` 헤더에 `sub` 값을 그대로 전달하는지 확인
3. JWT 토큰이 만료되지 않았는지 확인

### SSE 스트리밍 실패

**증상**: POST 요청 시 스트리밍이 시작되지 않음

**해결 방법**:
1. `Accept: text/event-stream` 헤더 포함 확인
2. Gateway 로그에서 `SseResponseHeaderFilter` 실행 여부 확인
3. Aura-Platform이 POST 엔드포인트를 지원하는지 확인

---

## 📞 문의 및 이슈

통합 과정에서 문제가 발생하면 다음을 확인하세요:

1. **포트 충돌**: `docs/AURA_PLATFORM_INTEGRATION_GUIDE.md` 참조
2. **사용자 식별자**: 이 문서의 "사용자 식별자 일관성" 섹션 참조
3. **SSE 스트리밍**: `docs/FRONTEND_INTEGRATION_GUIDE.md` 참조

---

**최종 업데이트**: 2026-01-16  
**담당자**: DWP Backend Team
