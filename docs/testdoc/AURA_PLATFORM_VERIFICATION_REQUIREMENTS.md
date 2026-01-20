# Aura-Platform 확인 요청 사항

> **작성일**: 2026-01-16  
> **대상**: Aura-Platform 개발팀  
> **목적**: 백엔드 통합 전 필수 확인 사항

---

## 🔍 필수 확인 사항

### 1. 포트 및 엔드포인트 설정

**✅ 확인 필요**: Aura-Platform이 포트 9000에서 실행되고 있는지 확인

**요구사항**:
- 포트: **9000** (변경 불가)
- 엔드포인트: `/aura/test/stream` (Gateway의 `/api/aura/test/stream`에서 StripPrefix=1로 변환)

**확인 방법**:
```bash
# 포트 확인
lsof -i :9000

# 서비스 실행 확인
curl http://localhost:9000/health  # 또는 health check 엔드포인트
```

**Gateway 라우팅**:
```
프론트엔드: POST /api/aura/test/stream
    ↓ (Gateway StripPrefix=1)
Aura-Platform: POST /aura/test/stream
```

---

### 2. POST 엔드포인트 구현

**✅ 확인 필요**: POST `/aura/test/stream` 엔드포인트가 구현되어 있는지 확인

**요구사항**:
- HTTP 메서드: **POST** (GET 아님)
- 요청 본문: JSON 형식 (`prompt`, `context` 필드 포함)
- 응답: SSE 스트림 (`text/event-stream`)

**FastAPI 구현 예시**:
```python
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
import json

app = FastAPI()

@app.post("/aura/test/stream")
async def stream_aura(request: Request):
    # 요청 본문 파싱
    body = await request.json()
    prompt = body.get("prompt")
    context = body.get("context", {})
    
    # SSE 스트림 생성
    async def event_generator():
        # 이벤트 생성 로직
        yield f"data: {json.dumps({'type': 'thought', 'content': '...'})}\n\n"
        yield f"data: {json.dumps({'type': 'plan_step', 'content': '...'})}\n\n"
        # ...
    
    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive"
        }
    )
```

**검증 방법**:
```bash
# POST 요청 테스트
curl -X POST http://localhost:9000/aura/test/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"prompt": "test", "context": {}}'
```

---

### 3. SSE 응답 헤더 설정

**✅ 확인 필요**: SSE 응답에 필수 헤더가 설정되어 있는지 확인

**필수 헤더**:
- `Content-Type: text/event-stream`
- `Cache-Control: no-cache`
- `Connection: keep-alive` (선택, 권장)

**FastAPI 구현 예시**:
```python
return StreamingResponse(
    event_generator(),
    media_type="text/event-stream",  # ✅ Content-Type 설정
    headers={
        "Cache-Control": "no-cache",  # ✅ Cache-Control 설정
        "Connection": "keep-alive"     # ✅ Connection 설정 (권장)
    }
)
```

**검증 방법**:
```bash
# 응답 헤더 확인
curl -X POST http://localhost:9000/aura/test/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"prompt": "test", "context": {}}' \
  -v 2>&1 | grep -i "content-type\|cache-control"
```

---

### 4. SSE 이벤트 ID 포함

**✅ 확인 필요**: SSE 이벤트에 `id:` 라인을 포함하는지 확인

**요구사항**:
- 각 이벤트에 고유한 `id:` 라인 포함
- 재연결 시 `Last-Event-ID` 헤더 처리

**SSE 이벤트 형식**:
```
id: 1706156400123
data: {"type":"thought","content":"..."}

id: 1706156400124
data: {"type":"plan_step","content":"..."}
```

**FastAPI 구현 예시**:
```python
import time

async def event_generator():
    event_id = int(time.time() * 1000)  # 밀리초 단위 타임스탬프
    
    # 이벤트 생성
    event_data = {"type": "thought", "content": "..."}
    yield f"id: {event_id}\n"  # ✅ id: 라인 포함
    yield f"data: {json.dumps(event_data)}\n\n"
    
    event_id += 1
    event_data = {"type": "plan_step", "content": "..."}
    yield f"id: {event_id}\n"  # ✅ id: 라인 포함
    yield f"data: {json.dumps(event_data)}\n\n"
```

**검증 방법**:
```bash
# SSE 응답에서 id: 라인 확인
curl -X POST http://localhost:9000/aura/test/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"prompt": "test", "context": {}}' | grep "^id:"
```

---

### 5. Last-Event-ID 헤더 처리

**✅ 확인 필요**: 재연결 시 `Last-Event-ID` 헤더를 처리하는지 확인

**요구사항**:
- 클라이언트가 `Last-Event-ID` 헤더로 재연결 시, 해당 ID 이후의 이벤트부터 재개
- Gateway가 `Last-Event-ID` 헤더를 Aura-Platform으로 전달

**FastAPI 구현 예시**:
```python
@app.post("/aura/test/stream")
async def stream_aura(request: Request):
    # Last-Event-ID 헤더 확인
    last_event_id = request.headers.get("Last-Event-ID")
    
    if last_event_id:
        # 재연결: 마지막 이벤트 ID 이후부터 재개
        last_id = int(last_event_id)
        # 중단된 지점부터 이벤트 재개 로직
        # ...
    
    # 요청 본문 파싱
    body = await request.json()
    prompt = body.get("prompt")
    context = body.get("context", {})
    
    # SSE 스트림 생성
    async def event_generator():
        # 이벤트 생성 로직
        # ...
    
    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache"}
    )
```

**검증 방법**:
```bash
# 첫 번째 연결
curl -X POST http://localhost:9000/aura/test/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"prompt": "test", "context": {}}'

# 재연결 (Last-Event-ID 포함)
curl -X POST http://localhost:9000/aura/test/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "Last-Event-ID: 1706156400123" \
  -d '{"prompt": "test", "context": {}}'
```

---

### 6. 요청 본문 파싱

**✅ 확인 필요**: POST 요청 본문에서 `prompt`와 `context`를 올바르게 파싱하는지 확인

**요청 본문 형식**:
```json
{
  "prompt": "사용자 질문",
  "context": {
    "url": "http://localhost:4200/mail",
    "userId": "user123",
    "tenantId": "tenant1"
  }
}
```

**FastAPI 구현 예시**:
```python
from pydantic import BaseModel

class StreamRequest(BaseModel):
    prompt: str
    context: dict = {}

@app.post("/aura/test/stream")
async def stream_aura(request: StreamRequest):
    prompt = request.prompt  # ✅ prompt 파싱
    context = request.context  # ✅ context 파싱
    
    # context에서 필요한 정보 추출
    url = context.get("url")
    userId = context.get("userId")
    tenantId = context.get("tenantId")
    
    # SSE 스트림 생성
    # ...
```

**검증 방법**:
- 요청 본문 로깅으로 `prompt`와 `context`가 올바르게 파싱되는지 확인
- `context`의 필수 필드가 누락되지 않았는지 확인

---

### 7. 헤더 전파 확인

**✅ 확인 필요**: Gateway에서 전달되는 헤더를 올바르게 처리하는지 확인

**전달되는 헤더**:
- `Authorization`: JWT 토큰
- `X-Tenant-ID`: 테넌트 ID
- `X-User-ID`: 사용자 ID
- `X-DWP-Source`: 요청 출처 (예: "FRONTEND")
- `X-DWP-Caller-Type`: 호출자 타입 (예: "AGENT")
- `Last-Event-ID`: 재연결 시 마지막 이벤트 ID

**FastAPI 구현 예시**:
```python
@app.post("/aura/test/stream")
async def stream_aura(request: Request):
    # 헤더 추출
    authorization = request.headers.get("Authorization")
    tenant_id = request.headers.get("X-Tenant-ID")
    user_id = request.headers.get("X-User-ID")
    source = request.headers.get("X-DWP-Source")
    caller_type = request.headers.get("X-DWP-Caller-Type")
    last_event_id = request.headers.get("Last-Event-ID")
    
    # JWT 토큰 검증 (필요 시)
    if authorization:
        token = authorization.replace("Bearer ", "")
        # JWT 검증 로직
        # ...
    
    # 요청 본문 파싱
    body = await request.json()
    # ...
```

**검증 방법**:
- 요청 로깅으로 모든 헤더가 올바르게 수신되는지 확인
- JWT 토큰이 유효한지 확인 (필요 시)

---

### 8. SSE 이벤트 형식

**✅ 확인 필요**: SSE 이벤트가 프론트엔드 명세에 맞는 형식인지 확인

**이벤트 타입**:
- `thought`: 사고 과정
- `plan_step`: 작업 계획 단계
- `tool_execution`: 도구 실행
- `hitl`: Human-in-the-loop 승인 요청
- `content`: 최종 콘텐츠
- `timeline_step_update`: 타임라인 단계 업데이트
- `plan_step_update`: 계획 단계 업데이트

**SSE 이벤트 형식**:
```
id: 1706156400123
data: {"type":"thought","content":"사고 과정","timestamp":1706156400}

id: 1706156400124
data: {"type":"plan_step","content":"작업 계획","timestamp":1706156400}

id: 1706156400125
data: [DONE]
```

**FastAPI 구현 예시**:
```python
async def event_generator():
    # thought 이벤트
    yield f"id: {event_id}\n"
    yield f"data: {json.dumps({
        'type': 'thought',
        'content': '사고 과정',
        'timestamp': int(time.time())
    })}\n\n"
    
    # plan_step 이벤트
    event_id += 1
    yield f"id: {event_id}\n"
    yield f"data: {json.dumps({
        'type': 'plan_step',
        'content': '작업 계획',
        'timestamp': int(time.time())
    })}\n\n"
    
    # 스트림 종료
    event_id += 1
    yield f"id: {event_id}\n"
    yield "data: [DONE]\n\n"
```

**검증 방법**:
- 각 이벤트 타입이 올바른 형식인지 확인
- `timestamp` 필드가 Unix timestamp (초 단위)인지 확인
- 스트림 종료 시 `data: [DONE]`이 전송되는지 확인

---

## 📋 확인 체크리스트

Aura-Platform 개발팀에서 다음 사항을 확인해주세요:

- [ ] 포트 9000에서 실행되는지 확인
- [ ] POST `/aura/test/stream` 엔드포인트가 구현되어 있는지 확인
- [ ] SSE 응답 헤더 (`Content-Type: text/event-stream`, `Cache-Control: no-cache`)가 설정되어 있는지 확인
- [ ] SSE 이벤트에 `id:` 라인이 포함되어 있는지 확인
- [ ] `Last-Event-ID` 헤더를 처리하는지 확인
- [ ] POST 요청 본문에서 `prompt`와 `context`를 올바르게 파싱하는지 확인
- [ ] Gateway에서 전달되는 헤더 (`Authorization`, `X-Tenant-ID`, `X-User-ID` 등)를 올바르게 처리하는지 확인
- [ ] SSE 이벤트 형식이 프론트엔드 명세에 맞는지 확인
- [ ] 스트림 종료 시 `data: [DONE]`이 전송되는지 확인

---

## 🧪 테스트 시나리오

### 시나리오 1: 기본 SSE 연결
```bash
curl -X POST http://localhost:9000/aura/test/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -d '{"prompt": "test", "context": {"url": "http://localhost:4200/mail"}}'
```

### 시나리오 2: 재연결 테스트
```bash
# 재연결 (Last-Event-ID 포함)
curl -X POST http://localhost:9000/aura/test/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -H "Last-Event-ID: 1706156400123" \
  -d '{"prompt": "test", "context": {"url": "http://localhost:4200/mail"}}'
```

### 시나리오 3: Gateway를 통한 접근
```bash
# Gateway를 통한 접근 (포트 8080)
curl -X POST http://localhost:8080/api/aura/test/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-User-ID: user123" \
  -d '{"prompt": "test", "context": {"url": "http://localhost:4200/mail"}}'
```

---

## 📞 문의 사항

확인 과정에서 문제가 발생하면 다음을 확인하세요:

1. **포트 충돌**: `lsof -i :9000`으로 포트 사용 확인
2. **SSE 응답 형식**: 응답 헤더와 이벤트 형식 확인
3. **헤더 전파**: Gateway 로그에서 헤더 전파 확인
4. **요청 본문 파싱**: 로그에서 `prompt`와 `context` 파싱 확인

---

**최종 업데이트**: 2026-01-16  
**담당자**: DWP Backend Team
