# 프론트엔드 확인 요청 사항

> **작성일**: 2026-01-16  
> **대상**: 프론트엔드 개발팀  
> **목적**: 백엔드 통합 전 필수 확인 사항

---

## 🔍 필수 확인 사항

### 1. JWT 사용자 식별자 매핑

**✅ 확인 필요**: JWT 토큰의 `sub` 클레임을 `X-User-ID` 헤더로 전달하는지 확인

**구현 예시**:
```javascript
// ✅ 올바른 구현
const token = localStorage.getItem('jwt_token');
const payload = JSON.parse(atob(token.split('.')[1]));
const userId = payload.sub;  // ✅ JWT의 sub 클레임 사용

// API 요청 시 헤더에 포함
fetch('/api/aura/test/stream', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'X-Tenant-ID': payload.tenant_id,
    'X-User-ID': userId  // ✅ JWT의 sub 값과 일치
  },
  body: JSON.stringify({
    prompt: "사용자 질문",
    context: { url: window.location.href }
  })
});
```

**❌ 잘못된 구현 (피해야 할 사항)**:
```javascript
// ❌ JWT의 다른 필드 사용
const userId = payload.userId;  // ❌ sub가 아닌 다른 필드

// ❌ 별도로 관리하는 userId 사용
const userId = userService.getCurrentUserId();  // ❌ JWT와 불일치 가능
```

**검증 방법**:
- 브라우저 개발자 도구에서 Network 탭 확인
- `X-User-ID` 헤더 값이 JWT의 `sub`와 일치하는지 확인
- HITL API 호출 시 `403 Forbidden` 오류가 발생하지 않는지 확인

---

### 2. POST 요청으로 SSE 연결

**✅ 확인 필요**: POST 메서드를 사용하여 SSE 스트림을 연결하는지 확인

**구현 예시**:
```javascript
// ✅ 올바른 구현 (POST 요청)
const response = await fetch('/api/aura/test/stream', {
  method: 'POST',  // ✅ POST 메서드 사용
  headers: {
    'Accept': 'text/event-stream',
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
    'X-Tenant-ID': tenantId,
    'X-User-ID': userId
  },
  body: JSON.stringify({
    prompt: "사용자 질문",
    context: {
      url: window.location.href,
      // ... 기타 context 데이터
    }
  })
});

const reader = response.body.getReader();
const decoder = new TextDecoder();

while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  
  const chunk = decoder.decode(value);
  // SSE 이벤트 파싱 및 처리
}
```

**❌ 잘못된 구현 (피해야 할 사항)**:
```javascript
// ❌ GET 요청 사용 (context 데이터 전달 불가)
const eventSource = new EventSource('/api/aura/test/stream?prompt=...');  // ❌
```

**검증 방법**:
- Network 탭에서 요청 메서드가 `POST`인지 확인
- 요청 본문에 `prompt`와 `context`가 포함되어 있는지 확인
- SSE 스트림이 정상적으로 수신되는지 확인

---

### 3. SSE 재연결 구현

**✅ 확인 필요**: `Last-Event-ID` 헤더를 사용한 재연결 구현

**구현 예시**:
```javascript
let lastEventId = null;

function connectSSE() {
  const headers = {
    'Accept': 'text/event-stream',
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
    'X-Tenant-ID': tenantId,
    'X-User-ID': userId
  };
  
  // 재연결 시 Last-Event-ID 헤더 추가
  if (lastEventId) {
    headers['Last-Event-ID'] = lastEventId;  // ✅ 재연결 지원
  }
  
  const response = await fetch('/api/aura/test/stream', {
    method: 'POST',
    headers: headers,
    body: JSON.stringify({
      prompt: "사용자 질문",
      context: { url: window.location.href }
    })
  });
  
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  
  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      // 연결 끊김 시 재연결
      setTimeout(connectSSE, 1000);
      break;
    }
    
    const chunk = decoder.decode(value);
    const lines = chunk.split('\n');
    
    for (const line of lines) {
      if (line.startsWith('id: ')) {
        lastEventId = line.substring(4).trim();  // ✅ 이벤트 ID 저장
      } else if (line.startsWith('data: ')) {
        const data = JSON.parse(line.substring(6));
        // 이벤트 처리
      }
    }
  }
}
```

**검증 방법**:
- 네트워크 연결을 의도적으로 끊고 재연결 테스트
- `Last-Event-ID` 헤더가 재연결 요청에 포함되는지 확인
- 중단된 지점부터 이벤트가 재개되는지 확인

---

### 4. CORS 헤더 설정

**✅ 확인 필요**: 필요한 헤더가 CORS preflight 요청에서 허용되는지 확인

**필수 헤더**:
- `Authorization`
- `X-Tenant-ID`
- `X-User-ID`
- `Content-Type`
- `Accept`
- `Last-Event-ID` (재연결 시)

**검증 방법**:
- 브라우저 콘솔에서 CORS 오류가 발생하지 않는지 확인
- OPTIONS 요청이 성공적으로 처리되는지 확인 (Network 탭)

---

### 5. 에러 처리

**✅ 확인 필요**: SSE 연결 실패 및 에러 상황 처리

**구현 예시**:
```javascript
try {
  const response = await fetch('/api/aura/test/stream', {
    method: 'POST',
    headers: { /* ... */ },
    body: JSON.stringify({ /* ... */ })
  });
  
  if (!response.ok) {
    // HTTP 에러 처리
    const error = await response.json();
    console.error('SSE connection failed:', error);
    return;
  }
  
  // SSE 스트림 처리
  const reader = response.body.getReader();
  // ...
  
} catch (error) {
  // 네트워크 에러 처리
  console.error('Network error:', error);
  // 재연결 시도
  setTimeout(connectSSE, 5000);
}
```

---

## 📋 확인 체크리스트

프론트엔드 개발팀에서 다음 사항을 확인해주세요:

- [ ] JWT의 `sub` 클레임을 `X-User-ID` 헤더로 전달하는지 확인
- [ ] POST 메서드를 사용하여 SSE 스트림을 연결하는지 확인
- [ ] 요청 본문에 `prompt`와 `context`가 포함되어 있는지 확인
- [ ] `Last-Event-ID` 헤더를 사용한 재연결이 구현되어 있는지 확인
- [ ] SSE 이벤트의 `id:` 라인을 파싱하여 저장하는지 확인
- [ ] CORS 오류가 발생하지 않는지 확인
- [ ] 에러 상황(연결 실패, 네트워크 오류)에 대한 처리가 구현되어 있는지 확인

---

## 🧪 테스트 시나리오

### 시나리오 1: 기본 SSE 연결
1. POST `/api/aura/test/stream` 요청 전송
2. SSE 스트림 수신 확인
3. 이벤트 파싱 및 UI 업데이트 확인

### 시나리오 2: 재연결 테스트
1. SSE 연결 중 네트워크 끊김 시뮬레이션
2. `Last-Event-ID` 헤더와 함께 재연결
3. 중단된 지점부터 이벤트 재개 확인

### 시나리오 3: JWT 검증 테스트
1. JWT의 `sub`와 `X-User-ID` 헤더 일치 확인
2. HITL API 호출 시 `403 Forbidden` 오류가 발생하지 않는지 확인

---

## 📞 문의 사항

확인 과정에서 문제가 발생하면 다음을 확인하세요:

1. **JWT 불일치 오류**: 브라우저 콘솔에서 `User ID mismatch` 오류 확인
2. **CORS 오류**: Network 탭에서 OPTIONS 요청 실패 확인
3. **SSE 연결 실패**: Gateway 로그에서 `RequestBodyLoggingFilter` 실행 확인

---

**최종 업데이트**: 2026-01-16  
**담당자**: DWP Backend Team
