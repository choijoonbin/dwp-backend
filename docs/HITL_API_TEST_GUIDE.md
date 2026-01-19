# HITL API 테스트 가이드

> **작성일**: 2026-01-16  
> **대상**: DWP Backend 개발팀  
> **목적**: HITL API 500 에러 해결 및 정상 동작 테스트 절차

---

## 🔍 HITL API 500 에러 원인

### 문제 상황
HITL 승인/거절 API 호출 시 `500 Internal Server Error` 발생

### 원인 분석
`HitlManager.approve()` 메서드에서:
```java
// 요청 조회
String requestJson = getApprovalRequest(requestId);
// ...
if (requestJson == null) {
    throw new BaseException(ErrorCode.NOT_FOUND, "Approval request not found: " + requestId);
}
```

**원인**: 
- 유효하지 않은 `requestId`로 인해 Redis에서 요청을 찾을 수 없음
- `requestId`는 SSE 스트림에서 `hitl` 이벤트를 수신한 후 생성되어야 함

---

## 📋 HITL API 테스트 절차

### 전체 플로우

```
1. SSE 스트림 시작
   ↓
2. Aura-Platform에서 hitl 이벤트 발행
   ↓
3. hitl 이벤트에서 requestId 추출
   ↓
4. HITL 승인/거절 API 호출 (requestId 사용)
   ↓
5. Redis Pub/Sub으로 신호 발행 확인
```

---

## 🔧 단계별 테스트 절차

### 1단계: 유효한 JWT 토큰 생성

**필요 사항**:
- Auth Server에서 유효한 JWT 토큰 발급
- JWT 토큰에 `sub` (사용자 ID), `tenant_id` 클레임 포함

**테스트 방법**:
```bash
# Auth Server에서 토큰 발급 (예시)
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "testpass"
  }'

# 응답에서 accessToken 추출
# 예: {"accessToken": "eyJhbGc..."}
```

**확인 사항**:
- JWT 토큰이 유효한지 확인
- `sub` 클레임이 있는지 확인
- `tenant_id` 클레임이 있는지 확인

---

### 2단계: SSE 스트림 시작

**목적**: Aura-Platform에서 `hitl` 이벤트를 수신하여 `requestId` 생성

**테스트 방법**:
```bash
# SSE 스트림 시작
curl -N -X POST http://localhost:8080/api/aura/test/stream \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {VALID_JWT_TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: {USER_ID_FROM_JWT_SUB}" \
  -d '{
    "prompt": "메일을 삭제해주세요",
    "context": {
      "url": "http://localhost:4200/mail",
      "activeApp": "mail",
      "pathname": "/mail"
    }
  }'
```

**예상 응답**:
```
data: {"type":"thought","content":"사용자 요청을 분석하고 있습니다..."}

data: {"type":"plan_step","data":{"id":"step-1","title":"메일 삭제 계획","status":"in_progress"}}

data: {"type":"hitl","data":{"requestId":"req-12345-abcde","actionType":"delete","description":"메일 삭제 승인이 필요합니다","sessionId":"session-67890"}}

data: {"type":"plan_step","data":{"id":"step-2","title":"메일 삭제 실행","status":"waiting"}}
```

**중요**: `hitl` 이벤트에서 `requestId`를 추출해야 합니다.

---

### 3단계: requestId 추출

**hitl 이벤트 형식**:
```json
{
  "type": "hitl",
  "data": {
    "requestId": "req-12345-abcde",  // ✅ 이 값을 추출
    "actionType": "delete",
    "description": "메일 삭제 승인이 필요합니다",
    "sessionId": "session-67890"
  }
}
```

**추출 방법**:
```bash
# SSE 스트림에서 hitl 이벤트를 찾아 requestId 추출
# 예: "req-12345-abcde"
```

---

### 4단계: HITL 승인 API 호출

**테스트 방법**:
```bash
# 3단계에서 추출한 requestId 사용
REQUEST_ID="req-12345-abcde"  # 실제 requestId로 교체
USER_ID="user123"  # JWT의 sub 값

curl -X POST http://localhost:8080/api/aura/hitl/approve/${REQUEST_ID} \
  -H "Authorization: Bearer {VALID_JWT_TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: ${USER_ID}" \
  -H "Content-Type: application/json" \
  -d "{
    \"userId\": \"${USER_ID}\"
  }"
```

**예상 응답**:
```json
{
  "success": true,
  "message": "Request approved successfully",
  "data": {
    "requestId": "req-12345-abcde",
    "sessionId": "session-67890",
    "status": "approved"
  }
}
```

**확인 사항**:
- HTTP 상태 코드: `200 OK`
- 응답에 `sessionId` 포함 확인
- Main Service 로그에서 승인 처리 확인

---

### 5단계: Redis Pub/Sub 신호 발행 확인

**테스트 방법**:
```bash
# Redis Pub/Sub 채널 구독 (별도 터미널)
docker exec -it dwp-redis redis-cli PSUBSCRIBE "hitl:channel:*"

# 또는
redis-cli -h localhost -p 6379 PSUBSCRIBE "hitl:channel:*"
```

**4단계에서 승인 API 호출 후 예상 신호**:
```
1) "pmessage"
2) "hitl:channel:session-67890"  # sessionId
3) "{\"timestamp\":1706156400,\"action\":\"approve\",\"requestId\":\"req-12345-abcde\",\"sessionId\":\"session-67890\"}"
```

**확인 사항**:
- Redis Pub/Sub 채널에 신호가 발행되는지 확인
- 신호 형식이 올바른지 확인:
  - `timestamp`: Unix timestamp (초 단위)
  - `action`: "approve" 또는 "reject"
  - `requestId`: 승인/거절한 요청 ID
  - `sessionId`: 에이전트 세션 ID

---

## 🧪 통합 테스트 스크립트

### 자동화된 테스트 스크립트

```bash
#!/bin/bash

# 설정
JWT_TOKEN="your-valid-jwt-token"
USER_ID="user123"  # JWT의 sub 값
TENANT_ID="tenant1"

echo "=== HITL API 통합 테스트 ==="
echo ""

# 1. SSE 스트림 시작 및 hitl 이벤트 수신
echo "1. SSE 스트림 시작 중..."
REQUEST_ID=$(curl -N -X POST http://localhost:8080/api/aura/test/stream \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JWT_TOKEN}" \
  -H "X-Tenant-ID: ${TENANT_ID}" \
  -H "X-User-ID: ${USER_ID}" \
  -d '{
    "prompt": "메일을 삭제해주세요",
    "context": {"url": "http://localhost:4200/mail"}
  }' 2>/dev/null | \
  grep -o '"requestId":"[^"]*"' | \
  head -1 | \
  cut -d'"' -f4)

if [ -z "$REQUEST_ID" ]; then
  echo "❌ requestId를 찾을 수 없습니다. hitl 이벤트를 확인하세요."
  exit 1
fi

echo "✅ requestId 추출: ${REQUEST_ID}"
echo ""

# 2. Redis Pub/Sub 구독 시작 (백그라운드)
echo "2. Redis Pub/Sub 구독 시작..."
docker exec -d dwp-redis redis-cli PSUBSCRIBE "hitl:channel:*" > /tmp/redis-pubsub.log 2>&1
sleep 1

# 3. HITL 승인 API 호출
echo "3. HITL 승인 API 호출..."
RESPONSE=$(curl -s -X POST http://localhost:8080/api/aura/hitl/approve/${REQUEST_ID} \
  -H "Authorization: Bearer ${JWT_TOKEN}" \
  -H "X-Tenant-ID: ${TENANT_ID}" \
  -H "X-User-ID: ${USER_ID}" \
  -H "Content-Type: application/json" \
  -d "{\"userId\": \"${USER_ID}\"}")

echo "응답: ${RESPONSE}"
echo ""

# 4. Redis Pub/Sub 신호 확인
echo "4. Redis Pub/Sub 신호 확인 (5초 대기)..."
sleep 5

if grep -q "approve" /tmp/redis-pubsub.log 2>/dev/null; then
  echo "✅ Redis Pub/Sub 신호 발행 확인"
else
  echo "⚠️ Redis Pub/Sub 신호를 찾을 수 없습니다."
fi

# 정리
pkill -f "redis-cli.*PSUBSCRIBE" 2>/dev/null

echo ""
echo "=== 테스트 완료 ==="
```

---

## 🔍 문제 해결 가이드

### 문제 1: requestId를 찾을 수 없음

**증상**: SSE 스트림에서 `hitl` 이벤트가 수신되지 않음

**해결 방법**:
1. Aura-Platform이 `hitl` 이벤트를 발행하는지 확인
2. 프롬프트가 HITL이 필요한 작업인지 확인 (예: 삭제, 메일 발송)
3. Aura-Platform 로그에서 `hitl` 이벤트 발행 확인

---

### 문제 2: HITL API 호출 시 500 에러

**증상**: 유효한 `requestId`로 호출했지만 500 에러 발생

**확인 사항**:
1. **Redis 연결 확인**:
   ```bash
   docker exec dwp-redis redis-cli PING
   # PONG 응답이어야 함
   ```

2. **Main Service Redis 설정 확인**:
   - `application.yml`에서 `spring.data.redis.host: localhost`
   - `spring.data.redis.port: 6379`

3. **Main Service 로그 확인**:
   ```bash
   tail -f /tmp/dwp-main-service.log | grep -i "redis\|hitl\|error"
   ```

4. **requestId가 Redis에 저장되었는지 확인**:
   ```bash
   docker exec dwp-redis redis-cli GET "hitl:request:${REQUEST_ID}"
   # 요청 데이터가 반환되어야 함
   ```

---

### 문제 3: Redis Pub/Sub 신호가 발행되지 않음

**증상**: HITL API 호출은 성공하지만 Redis Pub/Sub 신호가 없음

**확인 사항**:
1. **Redis Pub/Sub 채널 이름 확인**:
   - 채널 형식: `hitl:channel:{sessionId}`
   - `sessionId`는 `hitl` 이벤트의 `data.sessionId`

2. **Main Service 로그 확인**:
   ```bash
   tail -f /tmp/dwp-main-service.log | grep -i "pubsub\|convertAndSend\|hitl.*approved"
   ```

3. **Redis Pub/Sub 직접 테스트**:
   ```bash
   # 터미널 1: 구독
   docker exec -it dwp-redis redis-cli PSUBSCRIBE "hitl:channel:*"
   
   # 터미널 2: 발행 테스트
   docker exec dwp-redis redis-cli PUBLISH "hitl:channel:test-session" '{"test":"message"}'
   ```

---

## 📊 테스트 체크리스트

### 필수 준비 사항
- [ ] 유효한 JWT 토큰 발급
- [ ] JWT 토큰의 `sub` 값 확인
- [ ] `X-User-ID` 헤더에 `sub` 값 설정
- [ ] Redis Docker 컨테이너 실행 확인
- [ ] Main Service Redis 연결 확인

### 테스트 단계
- [ ] 1단계: SSE 스트림 시작
- [ ] 2단계: `hitl` 이벤트 수신
- [ ] 3단계: `requestId` 추출
- [ ] 4단계: HITL 승인 API 호출 (200 OK)
- [ ] 5단계: Redis Pub/Sub 신호 발행 확인

### 검증 사항
- [ ] HITL API 응답: `200 OK`
- [ ] 응답에 `sessionId` 포함
- [ ] Main Service 로그에서 승인 처리 확인
- [ ] Redis Pub/Sub 채널에 신호 발행 확인
- [ ] 신호 형식이 올바른지 확인

---

## 🎯 빠른 테스트 방법

### 방법 1: 수동 requestId 생성 (테스트용)

**주의**: 실제 운영 환경에서는 사용하지 마세요. 테스트 목적으로만 사용합니다.

```bash
# 1. Redis에 테스트용 HITL 요청 저장
REQUEST_ID="test-request-$(date +%s)"
SESSION_ID="test-session-$(date +%s)"
USER_ID="user123"
TENANT_ID="tenant1"

docker exec dwp-redis redis-cli SET "hitl:request:${REQUEST_ID}" "{\"requestId\":\"${REQUEST_ID}\",\"sessionId\":\"${SESSION_ID}\",\"actionType\":\"delete\",\"status\":\"pending\",\"createdAt\":$(date +%s)}"

# 2. HITL 승인 API 호출
curl -X POST http://localhost:8080/api/aura/hitl/approve/${REQUEST_ID} \
  -H "Authorization: Bearer {VALID_JWT_TOKEN}" \
  -H "X-Tenant-ID: ${TENANT_ID}" \
  -H "X-User-ID: ${USER_ID}" \
  -H "Content-Type: application/json" \
  -d "{\"userId\": \"${USER_ID}\"}"

# 3. Redis Pub/Sub 확인
docker exec -it dwp-redis redis-cli PSUBSCRIBE "hitl:channel:${SESSION_ID}"
```

---

## 📝 테스트 결과 기록

### 성공 케이스
- [ ] HITL API 호출: `200 OK`
- [ ] 응답 형식: 올바른 JSON 구조
- [ ] Redis Pub/Sub 신호: 발행 확인
- [ ] 신호 형식: 올바른 형식

### 실패 케이스
- [ ] 에러 메시지 기록
- [ ] Main Service 로그 확인
- [ ] Redis 연결 상태 확인
- [ ] requestId 유효성 확인

---

## 🔗 관련 문서

- [HITL Manager 구현](./dwp-main-service/src/main/java/com/dwp/services/main/service/HitlManager.java)
- [HITL Controller](./dwp-main-service/src/main/java/com/dwp/services/main/controller/HitlController.java)
- [프론트엔드 API 스펙](./docs/FRONTEND_API_SPEC.md) - hitl 이벤트 형식
- [백엔드 통합 테스트 체크리스트](./docs/BACKEND_INTEGRATION_TEST_CHECKLIST.md)

---

**최종 업데이트**: 2026-01-16  
**담당자**: DWP Backend Team
