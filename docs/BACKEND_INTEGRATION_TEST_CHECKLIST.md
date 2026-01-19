# DWP Backend 통합 테스트 체크리스트

> **작성일**: 2026-01-16  
> **대상**: DWP Backend 개발팀  
> **목적**: 백엔드 통합 테스트 필수 확인 사항

---

## 📋 DWP Backend 통합 테스트 항목

### 1. Gateway SSE 라우팅

**목표**: Gateway(8080)가 Aura-Platform(9000)의 SSE 스트림을 끊김 없이 프론트엔드로 전달

**테스트 항목**:
- [ ] **Chunked Transfer 확인**: `Transfer-Encoding: chunked` 헤더가 정상적으로 전달되는가?
- [ ] **Timeout 설정 확인**: Gateway의 `response-timeout: 300s` 설정이 적용되는가?
- [ ] **스트림 중단 없음**: SSE 스트림이 중간에 끊기지 않고 프론트엔드까지 전달되는가?
- [ ] **POST 요청 지원**: POST `/api/aura/test/stream` 요청에 대한 SSE 응답이 정상 작동하는가?

**테스트 방법**:
```bash
# 1. Gateway를 통한 SSE 연결 테스트
curl -N -X POST http://localhost:8080/api/aura/test/stream \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -d '{"prompt": "test", "context": {"url": "http://localhost:4200/mail"}}'

# 2. 응답 헤더 확인
# 예상 헤더:
# - Content-Type: text/event-stream
# - Cache-Control: no-cache
# - Transfer-Encoding: chunked

# 3. Gateway 로그 확인
tail -f /tmp/dwp-gateway.log | grep "SSE\|chunked\|Transfer-Encoding"
```

**확인 사항**:
- Gateway 로그에서 `SseResponseHeaderFilter` 실행 확인
- `Transfer-Encoding: chunked` 헤더가 응답에 포함되는지 확인
- 스트림이 300초 이상 지속되어도 끊기지 않는지 확인

**관련 파일**:
- `dwp-gateway/src/main/resources/application.yml` (타임아웃 설정)
- `dwp-gateway/src/main/java/com/dwp/gateway/config/SseResponseHeaderFilter.java`

---

### 2. Header 전파

**목표**: 필수 헤더가 Gateway를 통해 Aura-Platform까지 정확히 전달

**테스트 항목**:
- [ ] **X-Tenant-ID 전파**: Gateway에서 Aura-Platform으로 `X-Tenant-ID` 헤더가 전달되는가?
- [ ] **X-User-ID 전파**: Gateway에서 Aura-Platform으로 `X-User-ID` 헤더가 전달되는가?
- [ ] **X-DWP-Source 전파**: Gateway에서 Aura-Platform으로 `X-DWP-Source` 헤더가 전달되는가?
- [ ] **Authorization 전파**: JWT 토큰이 Aura-Platform까지 전달되는가?
- [ ] **Last-Event-ID 전파**: SSE 재연결 시 `Last-Event-ID` 헤더가 전달되는가?

**테스트 방법**:
```bash
# 1. Gateway 로그에서 헤더 전파 확인
tail -f /tmp/dwp-gateway.log | grep "Propagating\|Header"

# 2. Aura-Platform 로그에서 헤더 수신 확인
# (Aura-Platform에서 로그 출력 확인)

# 3. curl로 직접 테스트
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Authorization: Bearer {TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -H "X-DWP-Source: FRONTEND" \
  -H "Content-Type: application/json" \
  -d '{"prompt": "test", "context": {}}'
```

**확인 사항**:
- Gateway 로그에서 `HeaderPropagationFilter` 실행 확인
- 각 헤더가 로그에 출력되는지 확인
- Aura-Platform에서 헤더를 올바르게 수신하는지 확인

**관련 파일**:
- `dwp-gateway/src/main/java/com/dwp/gateway/config/HeaderPropagationFilter.java`

---

### 3. HITL API 연동

**목표**: HITL 승인/거절 API 호출 시 Redis Pub/Sub으로 승인 신호 발행

**테스트 항목**:
- [ ] **승인 API 호출**: `POST /api/aura/hitl/approve/{requestId}` 호출이 정상 작동하는가?
- [ ] **거절 API 호출**: `POST /api/aura/hitl/reject/{requestId}` 호출이 정상 작동하는가?
- [ ] **Redis Pub/Sub 발행**: 승인/거절 시 Redis Pub/Sub 채널에 신호가 발행되는가?
- [ ] **신호 형식 확인**: 발행된 신호가 올바른 형식(`timestamp`, `action`, `requestId`)인가?
- [ ] **Aura-Platform 수신**: Aura-Platform이 Redis Pub/Sub을 통해 신호를 수신하는가?

**테스트 방법**:
```bash
# 1. HITL 승인 요청 생성 (SSE 스트림에서 hitl 이벤트 수신 후)
# 2. 승인 API 호출
curl -X POST http://localhost:8080/api/aura/hitl/approve/{requestId} \
  -H "Authorization: Bearer {TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -H "Content-Type: application/json"

# 3. Redis Pub/Sub 확인
redis-cli
> PSUBSCRIBE hitl:channel:*
# 승인 신호 수신 확인

# 4. Main Service 로그 확인
tail -f /tmp/dwp-main-service.log | grep "HITL\|approve\|reject"
```

**확인 사항**:
- Main Service 로그에서 `HitlManager.approve()` 또는 `HitlManager.reject()` 실행 확인
- Redis Pub/Sub 채널에 신호가 발행되는지 확인
- 신호 형식이 올바른지 확인:
  ```json
  {
    "timestamp": 1706156400,  // Unix timestamp (초 단위)
    "action": "approve",       // 또는 "reject"
    "requestId": "req-123",
    "sessionId": "session-456"
  }
  ```

**관련 파일**:
- `dwp-main-service/src/main/java/com/dwp/services/main/service/HitlManager.java`
- `dwp-main-service/src/main/java/com/dwp/services/main/controller/HitlController.java`

---

### 4. AgentTask 영속화

**목표**: 에이전트가 발행하는 `plan_step` 상태가 DB에 실시간으로 동기화/저장

**테스트 항목**:
- [ ] **AgentTask 생성**: SSE 스트림 시작 시 `AgentTask` 엔티티가 생성되는가?
- [ ] **plan_steps 저장**: `plan_step` 이벤트가 수신될 때 `planSteps` 필드에 JSON으로 저장되는가?
- [ ] **상태 업데이트**: `AgentTask`의 상태(`status`)가 실시간으로 업데이트되는가?
- [ ] **HITL 요청 ID 매핑**: HITL 승인 요청 시 `hitlRequestId` 필드가 저장되는가?
- [ ] **DB 조회 확인**: 저장된 데이터가 DB에서 정상적으로 조회되는가?

**테스트 방법**:
```bash
# 1. SSE 스트림 시작 (프론트엔드 또는 curl)
curl -N -X POST http://localhost:8080/api/aura/test/stream \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -d '{"prompt": "test", "context": {}}'

# 2. plan_step 이벤트 수신 확인
# SSE 스트림에서 plan_step 이벤트 수신

# 3. DB 조회
psql -U dwp_user -d dwp_db
> SELECT id, status, plan_steps, hitl_request_id, created_at, updated_at 
  FROM agent_task 
  ORDER BY created_at DESC 
  LIMIT 1;

# 4. Main Service 로그 확인
tail -f /tmp/dwp-main-service.log | grep "AgentTask\|plan_step"
```

**확인 사항**:
- `AgentTask` 엔티티가 생성되는지 확인
- `planSteps` 필드에 JSON 데이터가 저장되는지 확인
- `plan_step` 이벤트 수신 시 `planSteps`가 업데이트되는지 확인
- HITL 승인 요청 시 `hitlRequestId`가 저장되는지 확인

**예상 DB 데이터**:
```json
{
  "id": 1,
  "status": "in_progress",
  "planSteps": "[{\"id\":\"step-1\",\"title\":\"분석\",\"status\":\"completed\"},{\"id\":\"step-2\",\"title\":\"실행\",\"status\":\"in_progress\"}]",
  "hitlRequestId": "req-123",
  "createdAt": "2026-01-16T10:00:00",
  "updatedAt": "2026-01-16T10:05:00"
}
```

**관련 파일**:
- `dwp-main-service/src/main/java/com/dwp/services/main/domain/AgentTask.java`
- `dwp-main-service/src/main/java/com/dwp/services/main/service/AgentTaskService.java` (존재하는 경우)

---

## 🔧 통합 테스트 시나리오

### 시나리오 1: 전체 플로우 테스트

**목적**: 프론트엔드 → Gateway → Aura-Platform → Gateway → 프론트엔드 전체 플로우 확인

**테스트 단계**:
1. 프론트엔드에서 POST `/api/aura/test/stream` 요청 전송
2. Gateway 로그에서 요청 수신 및 라우팅 확인
3. Aura-Platform 로그에서 요청 수신 및 SSE 스트림 시작 확인
4. Gateway 로그에서 SSE 스트림 중계 확인
5. 프론트엔드에서 SSE 이벤트 수신 확인
6. DB에서 `AgentTask` 생성 및 `planSteps` 저장 확인

**예상 결과**:
- ✅ 모든 단계에서 정상 작동
- ✅ SSE 스트림이 끊기지 않고 전달됨
- ✅ `AgentTask`가 DB에 저장됨

---

### 시나리오 2: HITL 승인 플로우 테스트

**목적**: HITL 승인 요청부터 Aura-Platform 신호 수신까지 전체 플로우 확인

**테스트 단계**:
1. SSE 스트림에서 `hitl` 이벤트 수신
2. 프론트엔드에서 `POST /api/aura/hitl/approve/{requestId}` 호출
3. Main Service 로그에서 승인 처리 확인
4. Redis Pub/Sub 채널에서 신호 발행 확인
5. Aura-Platform 로그에서 신호 수신 확인
6. SSE 스트림 재개 확인
7. DB에서 `hitlRequestId` 저장 확인

**예상 결과**:
- ✅ 승인 API 호출 성공
- ✅ Redis Pub/Sub 신호 발행 확인
- ✅ Aura-Platform 신호 수신 확인
- ✅ SSE 스트림 재개 확인

---

### 시나리오 3: Header 전파 테스트

**목적**: 모든 필수 헤더가 Gateway를 통해 Aura-Platform까지 전달되는지 확인

**테스트 단계**:
1. 프론트엔드에서 모든 필수 헤더 포함하여 요청 전송
2. Gateway 로그에서 헤더 전파 확인
3. Aura-Platform 로그에서 헤더 수신 확인
4. 각 헤더 값이 올바르게 전달되는지 확인

**예상 결과**:
- ✅ 모든 헤더가 Gateway 로그에 출력됨
- ✅ 모든 헤더가 Aura-Platform에 전달됨
- ✅ 헤더 값이 변경되지 않음

---

## 📊 테스트 결과 기록

### 테스트 환경
- **날짜**: 
- **테스터**: 
- **Gateway 포트**: 8080
- **Main Service 포트**: 8081
- **Aura-Platform 포트**: 9000
- **Redis 포트**: 6379
- **PostgreSQL 포트**: 5432

### 테스트 결과

| 항목 | 상태 | 비고 |
|------|------|------|
| Gateway SSE 라우팅 | [ ] | |
| Header 전파 | [ ] | |
| HITL API 연동 | [ ] | |
| AgentTask 영속화 | [ ] | |

---

## 🔍 문제 해결 가이드

### Gateway SSE 라우팅 문제

**증상**: SSE 스트림이 중간에 끊김
- **확인 사항**: Gateway 타임아웃 설정 (300초)
- **확인 사항**: `SseResponseHeaderFilter` 실행 여부
- **확인 사항**: 네트워크 연결 상태

### Header 전파 문제

**증상**: Aura-Platform에서 헤더를 수신하지 못함
- **확인 사항**: Gateway 로그에서 `HeaderPropagationFilter` 실행 확인
- **확인 사항**: 헤더 이름 대소문자 일치 여부
- **확인 사항**: Aura-Platform 헤더 파싱 로직

### HITL API 연동 문제

**증상**: Redis Pub/Sub 신호가 발행되지 않음
- **확인 사항**: Redis 연결 상태
- **확인 사항**: Main Service 로그에서 `HitlManager` 실행 확인
- **확인 사항**: Redis Pub/Sub 채널 이름 일치 여부

### AgentTask 영속화 문제

**증상**: `planSteps`가 DB에 저장되지 않음
- **확인 사항**: DB 연결 상태
- **확인 사항**: `AgentTask` 엔티티 생성 여부
- **확인 사항**: `plan_step` 이벤트 수신 여부

---

## 📞 문의 사항

테스트 과정에서 문제가 발생하면 다음을 확인하세요:

1. **Gateway 로그**: `/tmp/dwp-gateway.log`
2. **Main Service 로그**: `/tmp/dwp-main-service.log`
3. **Redis 상태**: `redis-cli PING`
4. **DB 연결**: `psql -U dwp_user -d dwp_db -c "SELECT 1;"`

---

**최종 업데이트**: 2026-01-16  
**담당자**: DWP Backend Team
