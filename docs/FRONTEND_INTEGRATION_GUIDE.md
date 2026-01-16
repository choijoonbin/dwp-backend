# 프론트엔드 통합 가이드

> **대상**: 프론트엔드 개발팀  
> **최종 업데이트**: 2026-01-16  
> **DWP Backend 버전**: v1.0

---

## 📋 개요

이 문서는 프론트엔드에서 DWP Backend와 Aura-Platform을 통합하기 위한 가이드입니다.

---

## 🌐 서비스 엔드포인트

### Gateway (모든 요청의 진입점)

**Base URL**: `http://localhost:8080`

모든 API 요청은 Gateway를 통해 라우팅됩니다.

---

## 🔐 인증

### JWT 토큰 발급

**엔드포인트**: `POST /api/auth/login` (구현 예정)

**현재**: JWT 토큰은 `dwp-auth-server`에서 발급받아야 합니다.

**토큰 형식**:
```
Authorization: Bearer {JWT_TOKEN}
```

**필수 헤더**:
- `Authorization: Bearer {JWT_TOKEN}` - JWT 인증 토큰
- `X-Tenant-ID: {tenant_id}` - 테넌트 ID
- `X-User-ID: {user_id}` - 사용자 ID (HITL 작업 시 필수)

**⚠️ 중요: 사용자 ID 일관성**
- `X-User-ID` 헤더 값은 **JWT의 `sub` 클레임과 일치**해야 합니다
- 프론트엔드에서 JWT를 디코딩하여 `sub` 값을 `X-User-ID`로 전달하세요
- 불일치 시 `403 Forbidden` 오류가 발생합니다

**예시**:
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

---

## 🤖 Aura-Platform (AI 에이전트) 통합

### SSE 스트리밍

**엔드포인트**: `POST /api/aura/test/stream`

**⚠️ 중요**: 프론트엔드 요구사항에 따라 `POST` 메서드를 사용하며, 요청 본문에 `prompt`와 `context`를 포함해야 합니다.

**요청 예시**:
```javascript
// EventSource는 GET만 지원하므로, fetch API를 사용해야 합니다
const response = await fetch('http://localhost:8080/api/aura/test/stream', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${jwtToken}`,
    'X-Tenant-ID': tenantId,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({
    prompt: "사용자 질문",
    context: {
      url: "http://localhost:4200/mail",
      path: "/mail",
      title: "메일 인박스",
      activeApp: "mail",
      itemId: "msg-123",
      metadata: {
        headings: ["받은 메일함", "중요 메일"],
        hasTables: true,
        tableCount: 1
      }
    }
  })
});

const reader = response.body.getReader();
const decoder = new TextDecoder();

while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  
  const chunk = decoder.decode(value);
  const lines = chunk.split('\n\n');
  
  for (const line of lines) {
    if (line.startsWith('event:')) {
      const eventType = line.split(':')[1].trim();
      // 이벤트 타입 처리
    } else if (line.startsWith('data:')) {
      const data = line.split(':')[1].trim();
      if (data === '[DONE]') {
        // 스트리밍 종료
        break;
      }
      const jsonData = JSON.parse(data);
      // 데이터 처리
    }
  }
}

eventSource.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('Event type:', event.type);
  console.log('Data:', data);
};

// 이벤트 타입별 처리
eventSource.addEventListener('thought', (event) => {
  const data = JSON.parse(event.data);
  // 사고 과정 표시
});

eventSource.addEventListener('plan_step', (event) => {
  const data = JSON.parse(event.data);
  // 실행 계획 단계 표시
});

eventSource.addEventListener('tool_execution', (event) => {
  const data = JSON.parse(event.data);
  // 도구 실행 상태 표시
});

eventSource.addEventListener('hitl', (event) => {
  const data = JSON.parse(event.data);
  // 승인 요청 UI 표시
  showApprovalRequest(data.data);
});

eventSource.addEventListener('content', (event) => {
  const data = JSON.parse(event.data);
  // 최종 결과 표시
});
```

**SSE 이벤트 형식**:
```
event: {type}
data: {json}\n\n
```

또는 간단한 형식:
```
data: {json}\n\n
```

스트림 종료:
```
data: [DONE]\n\n
```

**이벤트 타입**:
- `thought` / `thinking` - 사고 과정
- `plan_step` - 실행 계획 단계
- `plan_step_update` - 계획 단계 상태 업데이트 (선택)
- `tool_execution` / `action` - 도구 실행
- `hitl` / `approval_required` - 승인 요청
- `content` / `message` - 최종 결과
- `timeline_step_update` - 타임라인 단계 업데이트 (선택)

**상세 스펙**: [프론트엔드 API 스펙](./FRONTEND_API_SPEC.md) 참조

---

## ✅ HITL (Human-In-The-Loop) 승인

**중요**: HITL API는 Main Service에 있으며, Gateway를 통해 `/api/aura/hitl/**` 경로로 접근합니다.

### 승인 요청 조회

**엔드포인트**: `GET /api/aura/hitl/requests/{requestId}`

**요청 예시**:
```javascript
const response = await fetch(
  `http://localhost:8080/api/aura/hitl/requests/${requestId}`,
  {
    headers: {
      'Authorization': `Bearer ${jwtToken}`,
      'X-Tenant-ID': tenantId
    }
  }
);

const result = await response.json();
// result.data에 승인 요청 정보 (JSON 문자열)
```

---

### 승인 처리

**엔드포인트**: `POST /api/aura/hitl/approve/{requestId}`

**요청 예시**:
```javascript
const response = await fetch(
  `http://localhost:8080/api/aura/hitl/approve/${requestId}`,
  {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${jwtToken}`,
      'X-Tenant-ID': tenantId,
      'X-User-ID': userId,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      userId: userId
    })
  }
);

const result = await response.json();
// result.data.status === "approved"
```

**응답 형식**:
```json
{
  "status": "SUCCESS",
  "message": "Request approved successfully",
  "data": {
    "requestId": "req-12345",
    "sessionId": "session-abc",
    "status": "approved"
  },
  "success": true,
  "timestamp": "2026-01-16T12:00:00"
}
```

---

### 거절 처리

**엔드포인트**: `POST /api/aura/hitl/reject/{requestId}`

**요청 예시**:
```javascript
const response = await fetch(
  `http://localhost:8080/api/aura/hitl/reject/${requestId}`,
  {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${jwtToken}`,
      'X-Tenant-ID': tenantId,
      'X-User-ID': userId,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      userId: userId,
      reason: "사용자 거절 사유" // 선택
    })
  }
);

const result = await response.json();
// result.data.status === "rejected"
```

**응답 형식**:
```json
{
  "status": "SUCCESS",
  "message": "Request rejected",
  "data": {
    "requestId": "req-12345",
    "sessionId": "session-abc",
    "status": "rejected",
    "reason": "사용자 거절 사유"
  },
  "success": true,
  "timestamp": "2026-01-16T12:00:00"
}
```

---

## 📡 API 응답 형식

모든 API 응답은 다음 형식을 따릅니다:

```typescript
interface ApiResponse<T> {
  status: "SUCCESS" | "ERROR";
  message: string;
  data: T;
  success: boolean;
  timestamp: string;
  errorCode?: string;
}
```

---

## 🔄 HITL 승인 플로우

1. **SSE 스트리밍 시작**
   ```javascript
   const eventSource = new EventSource('/api/aura/test/stream?message=...');
   ```

2. **HITL 이벤트 수신**
   ```javascript
   eventSource.addEventListener('hitl', (event) => {
     const data = JSON.parse(event.data);
     const requestId = data.data.requestId;
     // 승인 UI 표시
   });
   ```

3. **사용자 승인/거절**
   ```javascript
   // 승인
   await fetch(`/api/aura/hitl/approve/${requestId}`, { ... });
   
   // 거절
   await fetch(`/api/aura/hitl/reject/${requestId}`, { ... });
   ```

4. **SSE 스트리밍 계속**
   - 승인/거절 후 에이전트가 작업을 계속 진행합니다.

---

## ⚙️ 환경 변수

### 개발 환경

```bash
# Gateway URL
VITE_API_BASE_URL=http://localhost:8080

# Aura-Platform 직접 접근 (필요시)
VITE_AURA_PLATFORM_URL=http://localhost:9000
```

---

## 🚨 주의사항

### 1. CORS 설정

Gateway는 다음 Origin을 허용합니다:
- `http://localhost:4200` (기본값)
- 환경 변수 `CORS_ALLOWED_ORIGINS`로 설정 가능

**프론트엔드 개발 서버 포트가 다르면**:
```bash
# Gateway 실행 시 환경 변수 설정
export CORS_ALLOWED_ORIGINS=http://localhost:3039,http://localhost:4200
```

---

### 2. SSE 연결 관리

- SSE 연결은 자동으로 재연결됩니다.
- 연결 종료 시 `eventSource.close()` 호출 권장
- 타임아웃: 300초 (5분)

---

### 3. JWT 토큰 갱신

- JWT 토큰은 만료 시간(`exp`)을 확인해야 합니다.
- 토큰 만료 시 재발급 필요

---

## 📝 예제 코드

### React 예제

```typescript
import { useState, useEffect } from 'react';

function AuraChat() {
  const [messages, setMessages] = useState([]);
  const [approvalRequest, setApprovalRequest] = useState(null);
  const [isStreaming, setIsStreaming] = useState(false);

  const startStreaming = async (prompt: string, context?: any) => {
    const token = localStorage.getItem('jwt_token');
    const tenantId = localStorage.getItem('tenant_id');
    
    setIsStreaming(true);
    
    const response = await fetch('http://localhost:8080/api/aura/test/stream', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'X-Tenant-ID': tenantId,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        prompt,
        context: context || {}
      })
    });

    const reader = response.body?.getReader();
    const decoder = new TextDecoder();
    
    if (!reader) return;

    let buffer = '';
    
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        setIsStreaming(false);
        break;
      }
      
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n\n');
      buffer = lines.pop() || '';
      
      for (const line of lines) {
        if (line.startsWith('event:')) {
          const eventType = line.split(':')[1].trim();
          // 이벤트 타입 저장
        } else if (line.startsWith('data:')) {
          const data = line.split(':')[1].trim();
          if (data === '[DONE]') {
            setIsStreaming(false);
            break;
          }
          
          try {
            const jsonData = JSON.parse(data);
            handleEvent(jsonData);
          } catch (e) {
            console.error('Failed to parse SSE data:', e);
          }
        }
      }
    }
  };
  
  const handleEvent = (data: any) => {
    switch (data.type) {
      case 'thought':
      case 'thinking':
        // 사고 과정 처리
        break;
      case 'plan_step':
        // 작업 계획 처리
        break;
      case 'tool_execution':
      case 'action':
        // 도구 실행 처리
        break;
      case 'hitl':
      case 'approval_required':
        // 승인 요청 처리
        setApprovalRequest(data.data || data);
        break;
      case 'content':
      case 'message':
        // 최종 결과 처리
        break;
    }
  };

    es.addEventListener('thought', (event) => {
      const data = JSON.parse(event.data);
      setMessages(prev => [...prev, { type: 'thought', ...data }]);
    });

    es.addEventListener('hitl', (event) => {
      const data = JSON.parse(event.data);
      setApprovalRequest(data.data);
    });

    es.addEventListener('content', (event) => {
      const data = JSON.parse(event.data);
      setMessages(prev => [...prev, { type: 'content', ...data }]);
    });

    setEventSource(es);
  };

  const handleApproval = async (requestId: string, approved: boolean) => {
    const token = localStorage.getItem('jwt_token');
    const tenantId = localStorage.getItem('tenant_id');
    const userId = localStorage.getItem('user_id');

    const endpoint = approved 
      ? `/api/aura/hitl/approve/${requestId}`
      : `/api/aura/hitl/reject/${requestId}`;

    await fetch(`http://localhost:8080${endpoint}`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'X-Tenant-ID': tenantId,
        'X-User-ID': userId,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        userId,
        ...(approved ? {} : { reason: '사용자 거절' })
      })
    });

    setApprovalRequest(null);
  };

  useEffect(() => {
    return () => {
      setIsStreaming(false);
    };
  }, []);

  return (
    <div>
      {/* 메시지 표시 */}
      {messages.map((msg, idx) => (
        <div key={idx}>{JSON.stringify(msg)}</div>
      ))}

      {/* 승인 요청 UI */}
      {approvalRequest && (
        <div>
          <p>승인이 필요합니다: {approvalRequest.actionType}</p>
          <button onClick={() => handleApproval(approvalRequest.requestId, true)}>
            승인
          </button>
          <button onClick={() => handleApproval(approvalRequest.requestId, false)}>
            거절
          </button>
        </div>
      )}
    </div>
  );
}
```

---

## 🔗 관련 문서

- [프론트엔드 API 스펙](./FRONTEND_API_SPEC.md) - 프론트엔드에서 전달받은 상세 API 스펙
- [Aura-Platform Backend 전달 문서](./AURA_PLATFORM_BACKEND_HANDOFF.md)
- [Aura-Platform 통합 가이드](./AURA_PLATFORM_INTEGRATION_GUIDE.md)

---

**문서 버전**: v1.0  
**최종 업데이트**: 2026-01-16
