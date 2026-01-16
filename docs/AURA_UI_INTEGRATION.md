# Aura AI UI 백엔드 연동 가이드

## 개요

프론트엔드 Aura AI UI 명세에 맞춰 백엔드 연동 로직이 구현되었습니다.

## 구현 완료 내역

### ✅ 1. SSE Proxy 구현

**Gateway 라우팅:**
- `/api/aura/test/stream` → `http://localhost:8000/aura/test/stream`
- `StripPrefix=1` 필터로 경로 변환
- `PreserveHostHeader` 필터로 헤더 전파 보장

**타임아웃 설정:**
- Response Timeout: 300초 (5분)
- Connect Timeout: 10초

**사용 예시:**
```bash
curl -N -H "Accept: text/event-stream" \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "X-Tenant-ID: tenant1" \
  http://localhost:8080/api/aura/test/stream
```

### ✅ 2. HITL Manager 구현

**API 엔드포인트:**
- `POST /api/main/hitl/approve/{requestId}`: 승인 처리
- `POST /api/main/hitl/reject/{requestId}`: 거절 처리
- `GET /api/main/hitl/requests/{requestId}`: 승인 요청 조회
- `GET /api/main/hitl/signals/{sessionId}`: 신호 조회 (에이전트용)

**Redis 세션 관리:**
- 승인 요청 저장: `hitl:request:{requestId}` (TTL: 30분)
- 세션 정보 저장: `hitl:session:{sessionId}` (TTL: 60분)
- 신호 발행: `hitl:signal:{sessionId}` (TTL: 5분)
- Pub/Sub 채널: `hitl:channel:{sessionId}`

**사용 예시:**
```bash
# 승인
curl -X POST http://localhost:8080/api/main/hitl/approve/{requestId} \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -H "Content-Type: application/json" \
  -d '{"userId": "user123"}'

# 거절
curl -X POST http://localhost:8080/api/main/hitl/reject/{requestId} \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -H "Content-Type: application/json" \
  -d '{"userId": "user123", "reason": "사용자 거절"}'
```

### ✅ 3. AgentTask 엔티티 확장

**추가된 필드:**
- `planSteps` (TEXT): AI 에이전트의 실행 계획 단계 (JSON 형식)

**예시 데이터:**
```json
{
  "planSteps": "[{\"step\": 1, \"action\": \"analyze\", \"status\": \"completed\"}, {\"step\": 2, \"action\": \"generate_report\", \"status\": \"in_progress\"}]"
}
```

### ✅ 4. Context 전파

**전파되는 헤더:**
- `Authorization`: JWT 토큰
- `X-Tenant-ID`: 테넌트 식별자
- `X-DWP-Source`: 요청 출처 (AURA, FRONTEND, INTERNAL, BATCH)
- `X-User-ID`: 사용자 식별자

**Gateway 필터:**
- `HeaderPropagationFilter`: 모든 요청 헤더를 다운스트림 서비스로 전파
- `PreserveHostHeader`: 호스트 헤더 보존

**Context JSON 전파:**
프론트엔드에서 보낸 `context` JSON 객체는 요청 본문에 포함되어 Aura-Platform으로 전달됩니다.

### ✅ 5. 보안 인터셉터

**HITL 보안 검증:**
- `HitlSecurityInterceptor`: HITL 작업 시 JWT 권한 재검증
- 필수 헤더 확인: `Authorization`, `X-Tenant-ID`, `X-User-ID`

**검증 로직:**
1. Authorization 헤더 존재 확인
2. JWT 토큰 형식 확인 (Bearer 토큰)
3. X-Tenant-ID 헤더 확인
4. X-User-ID 헤더 확인

**적용 경로:**
- `/main/hitl/approve/**`
- `/main/hitl/reject/**`
- 제외: `/main/hitl/signals/**` (에이전트용)

## 아키텍처

```
┌─────────────┐
│  Frontend   │
│  (Aura UI)  │
└──────┬──────┘
       │
       │ HTTP Request
       │ (Authorization, X-Tenant-ID, context)
       ▼
┌─────────────────────────────────────┐
│         Gateway (8080)              │
│  - HeaderPropagationFilter         │
│  - SSE Proxy                        │
│  - CORS 설정                        │
└──────┬──────────────────────────────┘
       │
       ├─────────────────┬──────────────────┐
       │                 │                  │
       ▼                 ▼                  ▼
┌─────────────┐  ┌──────────────┐  ┌─────────────┐
│ Aura-       │  │ Main Service │  │ Auth Server │
│ Platform    │  │ (8081)       │  │ (8000)      │
│ (8000)      │  │ - HITL Mgr   │  │ - JWT       │
│ - SSE Stream│  │ - AgentTask  │  │             │
│ - HITL Wait │  │ - Redis      │  │             │
└─────────────┘  └──────────────┘  └─────────────┘
```

## 데이터 흐름

### 1. SSE 스트림 요청

```
Frontend → Gateway → Aura-Platform
  GET /api/aura/test/stream
  Headers: Authorization, X-Tenant-ID, context (body)
  
Gateway 변환:
  /api/aura/test/stream → /aura/test/stream
  모든 헤더 전파
```

### 2. HITL 승인 프로세스

```
1. Aura-Platform이 HITL 요청 생성
   → Redis에 저장: hitl:request:{requestId}

2. Frontend가 승인 요청 조회
   → GET /api/main/hitl/requests/{requestId}

3. 사용자가 승인/거절
   → POST /api/main/hitl/approve/{requestId}
   → HitlSecurityInterceptor: JWT 재검증
   → HitlManager: Redis에 신호 발행

4. Aura-Platform이 신호 수신
   → Redis Pub/Sub: hitl:channel:{sessionId}
   → 스트리밍 재개
```

## 설정

### Gateway 설정

`dwp-gateway/src/main/resources/application.yml`:
```yaml
spring:
  cloud:
    gateway:
      httpclient:
        response-timeout: 300s  # SSE 타임아웃
        connect-timeout: 10000
      routes:
        - id: aura-platform
          uri: ${AURA_PLATFORM_URI:http://localhost:8000}
          predicates:
            - Path=/api/aura/**
          filters:
            - StripPrefix=1
            - PreserveHostHeader
```

### Redis 설정

`dwp-main-service/src/main/resources/application.yml`:
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
```

## 테스트

### 1. SSE 스트림 테스트

```bash
# JWT 토큰 생성
TOKEN=$(cd dwp-auth-server && python3 test_jwt_for_aura.py --token-only)

# SSE 스트림 요청
curl -N -H "Accept: text/event-stream" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: tenant1" \
  http://localhost:8080/api/aura/test/stream
```

### 2. HITL 승인 테스트

```bash
# 승인 요청 조회
curl http://localhost:8080/api/main/hitl/requests/{requestId} \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: tenant1"

# 승인 처리
curl -X POST http://localhost:8080/api/main/hitl/approve/{requestId} \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -H "Content-Type: application/json" \
  -d '{"userId": "user123"}'
```

## 보안 고려사항

1. **JWT 재검증**: HITL 작업 시 반드시 JWT 토큰을 재검증합니다.
2. **헤더 검증**: 필수 헤더(Authorization, X-Tenant-ID, X-User-ID)가 없으면 거부합니다.
3. **세션 관리**: Redis에 저장된 세션은 TTL로 자동 만료됩니다.
4. **멀티테넌시**: X-Tenant-ID를 통해 테넌트별 격리를 보장합니다.

## 다음 단계

1. JWT 토큰 검증 로직 강화 (현재는 헤더 존재만 확인)
2. Aura-Platform과의 실제 연동 테스트
3. 에러 처리 및 재시도 로직 추가
4. 모니터링 및 로깅 강화
