# 로그인 API 문제 해결 가이드

> **작성일**: 2026-01-19  
> **문제**: `/api/auth/login` 요청 시 `username`과 `tenantId`가 `null`로 전달됨

---

## 🔍 문제 현상

로그인 요청 시 다음과 같은 Validation 에러가 발생합니다:

```
Validation failed for argument [0] in public com.dwp.core.common.ApiResponse<com.dwp.services.auth.dto.LoginResponse> 
com.dwp.services.auth.controller.AuthController.login(com.dwp.services.auth.dto.LoginRequest) 
with 2 errors: 
[Field error in object 'loginRequest' on field 'username': rejected value [null]; 
Field error in object 'loginRequest' on field 'tenantId': rejected value [null]]
```

---

## ✅ 프론트엔드 확인 사항

### 1. 요청 Body 형식 확인

**필수 필드**:
- `username` (String, 필수)
- `password` (String, 필수)
- `tenantId` (String, 필수)

**올바른 요청 예시**:
```javascript
// ✅ 올바른 구현
const response = await fetch('http://localhost:8080/api/auth/login', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',  // ⚠️ 필수!
    'X-Tenant-ID': 'default'  // 헤더로도 전달 가능 (body 우선)
  },
  body: JSON.stringify({
    username: 'testuser',
    password: 'testpassword',
    tenantId: 'default'
  })
});
```

### 2. Content-Type 헤더 확인

**⚠️ 중요**: `Content-Type: application/json` 헤더가 반드시 포함되어야 합니다.

```javascript
// ❌ 잘못된 구현 (Content-Type 누락)
fetch('http://localhost:8080/api/auth/login', {
  method: 'POST',
  body: JSON.stringify({ username: 'test', password: 'test', tenantId: 'default' })
  // Content-Type 헤더가 없으면 서버가 body를 파싱하지 못함
});

// ✅ 올바른 구현
fetch('http://localhost:8080/api/auth/login', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json'  // ✅ 필수
  },
  body: JSON.stringify({ username: 'test', password: 'test', tenantId: 'default' })
});
```

### 3. 요청 Body 확인

브라우저 개발자 도구에서 다음을 확인하세요:

1. **Network 탭** 열기
2. `/api/auth/login` 요청 선택
3. **Request Payload** 확인:
   ```json
   {
     "username": "testuser",
     "password": "testpassword",
     "tenantId": "default"
   }
   ```
4. **Request Headers** 확인:
   ```
   Content-Type: application/json
   ```

### 4. Axios 사용 시 확인

```javascript
// ✅ Axios 올바른 사용법
import axios from 'axios';

const response = await axios.post('http://localhost:8080/api/auth/login', {
  username: 'testuser',
  password: 'testpassword',
  tenantId: 'default'
}, {
  headers: {
    'Content-Type': 'application/json'
  }
});

// Axios는 기본적으로 JSON을 직렬화하지만, 명시적으로 헤더를 설정하는 것이 안전합니다.
```

---

## 🔧 백엔드 확인 사항

### Gateway 로그 확인

Gateway가 재시작된 후, 다음 로그를 확인하세요:

```
POST request body for Auth Server: path=/api/auth/login, bodyLength=XX, bodyPreview={...}
✅ Request body contains required fields: username and tenantId
```

또는

```
⚠️ Request body may be missing required fields (username or tenantId)
⚠️ POST request body is empty: path=/api/auth/login
```

### Auth Server 로그 확인

Auth Server에서 요청을 받았는지 확인:

```
Securing POST /auth/login
Secured POST /auth/login
```

---

## 📋 체크리스트

프론트엔드에서 다음을 확인하세요:

- [ ] `Content-Type: application/json` 헤더가 포함되어 있는가?
- [ ] 요청 body에 `username`, `password`, `tenantId`가 모두 포함되어 있는가?
- [ ] `JSON.stringify()`를 사용하여 body를 직렬화했는가?
- [ ] 브라우저 개발자 도구 Network 탭에서 Request Payload가 올바른가?
- [ ] Gateway 로그에서 요청 body가 전달되는지 확인했는가?

---

## 🐛 디버깅 방법

### 1. 브라우저 개발자 도구 확인

```javascript
// 디버깅용 로그 추가
const loginData = {
  username: 'testuser',
  password: 'testpassword',
  tenantId: 'default'
};

console.log('Request body:', JSON.stringify(loginData));

const response = await fetch('http://localhost:8080/api/auth/login', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json'
  },
  body: JSON.stringify(loginData)
});

console.log('Response status:', response.status);
const result = await response.json();
console.log('Response:', result);
```

### 2. curl로 직접 테스트

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: default" \
  -d '{
    "username": "testuser",
    "password": "testpassword",
    "tenantId": "default"
  }'
```

성공 시 응답:
```json
{
  "status": "SUCCESS",
  "message": "로그인에 성공했습니다.",
  "data": {
    "accessToken": "...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "userId": "testuser",
    "tenantId": "default"
  }
}
```

---

## 📝 참고

- **API 엔드포인트**: `POST /api/auth/login`
- **Gateway**: `http://localhost:8080`
- **Auth Server**: `http://localhost:8001` (직접 접근 불가, Gateway 통해서만)
- **요청 Body 필드**: `username`, `password`, `tenantId` (모두 필수)
