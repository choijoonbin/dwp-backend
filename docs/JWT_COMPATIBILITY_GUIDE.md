# JWT Python-Java 호환성 가이드

Aura-Platform (Python)에서 생성한 JWT 토큰을 DWP Backend (Java/Spring)에서 검증하는 방법입니다.

## ⚠️ 중요: Python 코드 수정 필요

### 문제점
제시하신 Python 코드에서 `exp`와 `iat` 필드에 `datetime` 객체를 직접 넣으면 **JWT 표준에 맞지 않습니다**.

JWT 표준에서는 `exp`와 `iat`가 **Unix timestamp (초 단위 정수)**여야 합니다.

### ❌ 잘못된 코드
```python
from datetime import datetime, timedelta, timezone
from jose import jwt

payload = {
    "sub": "backend_user_001",
    "tenant_id": "tenant1",
    "email": "user@dwp.com",
    "role": "user",
    "exp": datetime.now(timezone.utc) + timedelta(hours=1),  # ❌ datetime 객체
    "iat": datetime.now(timezone.utc),  # ❌ datetime 객체
}

token = jwt.encode(payload, SECRET_KEY, algorithm="HS256")
```

### ✅ 올바른 코드
```python
from datetime import datetime, timedelta, timezone
from jose import jwt
import os
from dotenv import load_dotenv

load_dotenv()

# 환경 변수에서 시크릿 키 로드
SECRET_KEY = os.getenv("JWT_SECRET", "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256")
ALGORITHM = "HS256"

# 현재 시간 (UTC)
now = datetime.now(timezone.utc)
expiration = now + timedelta(hours=1)

# JWT payload 생성
# exp와 iat는 Unix timestamp (초 단위 정수)로 변환
payload = {
    "sub": "backend_user_001",
    "tenant_id": "tenant1",
    "email": "user@dwp.com",
    "role": "user",
    "exp": int(expiration.timestamp()),  # ✅ Unix timestamp로 변환
    "iat": int(now.timestamp()),  # ✅ Unix timestamp로 변환
}

# 토큰 생성
token = jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)
print(f"JWT Token for Aura-Platform:\n{token}")
```

## 🔑 시크릿 키 관리

### 환경 변수 설정
**`.env` 파일 (Aura-Platform):**
```bash
JWT_SECRET=your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256
```

**`application.yml` (DWP Backend):**
```yaml
jwt:
  secret: ${JWT_SECRET:your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256}
```

**Docker Compose (공유):**
```yaml
services:
  aura-platform:
    environment:
      - JWT_SECRET=${JWT_SECRET}
  
  dwp-auth-server:
    environment:
      - JWT_SECRET=${JWT_SECRET}
```

### 시크릿 키 생성
```bash
# 256비트(32바이트) 이상의 랜덤 키 생성
openssl rand -base64 32

# 또는 Python으로 생성
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
```

## 🧪 테스트 방법

### 1. Python에서 토큰 생성
```python
# test_jwt_generation.py
from datetime import datetime, timedelta, timezone
from jose import jwt
import os
from dotenv import load_dotenv

load_dotenv()

SECRET_KEY = os.getenv("JWT_SECRET", "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256")
ALGORITHM = "HS256"

now = datetime.now(timezone.utc)
expiration = now + timedelta(hours=1)

payload = {
    "sub": "backend_user_001",
    "tenant_id": "tenant1",
    "email": "user@dwp.com",
    "role": "user",
    "exp": int(expiration.timestamp()),
    "iat": int(now.timestamp()),
}

token = jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)
print(f"Generated JWT Token:\n{token}\n")

# 토큰 검증 (자체 검증)
decoded = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
print(f"Decoded Payload:\n{decoded}")
```

### 2. Java에서 토큰 검증
```bash
# DWP Backend 테스트 실행
cd dwp-backend
./gradlew :dwp-auth-server:test --tests "JwtCompatibilityTest"
```

### 3. 실제 API 호출 테스트
```bash
# 1. Python에서 토큰 생성
python test_jwt_generation.py
# 출력: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...

# 2. DWP Backend API 호출
curl -X GET http://localhost:8080/api/auth/info \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."

# 3. Aura-Platform에서 DWP Backend 호출
curl -X GET http://localhost:8080/api/main/agent/tasks \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "X-DWP-Source: AURA" \
  -H "X-Tenant-ID: tenant1"
```

## 📋 JWT 클레임 구조

### 표준 클레임 (JWT 표준)
| 클레임 | 타입 | 설명 | 필수 |
|--------|------|------|------|
| `sub` | String | Subject (사용자 ID) | ✅ |
| `exp` | Number | Expiration Time (Unix timestamp) | ✅ |
| `iat` | Number | Issued At (Unix timestamp) | ✅ |
| `nbf` | Number | Not Before (Unix timestamp) | ❌ |
| `iss` | String | Issuer | ❌ |
| `aud` | String/Array | Audience | ❌ |

### 커스텀 클레임 (DWP)
| 클레임 | 타입 | 설명 | 필수 |
|--------|------|------|------|
| `tenant_id` | String | 테넌트 ID | ✅ |
| `email` | String | 사용자 이메일 | ✅ |
| `role` | String | 사용자 역할 | ✅ |

## 🔍 문제 해결

### 문제 1: "Invalid token" 에러
**원인**: 시크릿 키 불일치 또는 토큰 형식 오류

**해결**:
```bash
# 1. 시크릿 키 확인
echo $JWT_SECRET

# 2. 토큰 디코딩 (Python)
python3 -c "from jose import jwt; print(jwt.decode('YOUR_TOKEN', 'YOUR_SECRET', algorithms=['HS256']))"

# 3. 토큰 만료 확인
python3 -c "from jose import jwt; import json; print(json.dumps(jwt.get_unverified_claims('YOUR_TOKEN'), indent=2))"
```

### 문제 2: "exp claim is not a number" 에러
**원인**: `exp` 필드가 datetime 객체로 저장됨

**해결**: `int(timestamp)`로 변환
```python
# ❌ 잘못된 코드
"exp": datetime.now(timezone.utc) + timedelta(hours=1)

# ✅ 올바른 코드
"exp": int((datetime.now(timezone.utc) + timedelta(hours=1)).timestamp())
```

### 문제 3: "Secret key too short" 에러
**원인**: HS256 알고리즘은 최소 256비트(32바이트) 키가 필요

**해결**: 더 긴 시크릿 키 사용
```python
# 최소 32바이트
SECRET_KEY = "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256"
```

## 📚 참고 자료

### Python 라이브러리
- [python-jose 문서](https://python-jose.readthedocs.io/)
- [JWT.io](https://jwt.io/) - 토큰 디버깅 도구

### Java/Spring 라이브러리
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [Nimbus JOSE + JWT](https://connect2id.com/products/nimbus-jose-jwt)

### JWT 표준
- [RFC 7519 - JSON Web Token (JWT)](https://tools.ietf.org/html/rfc7519)

## ✅ 체크리스트

### Python 코드
- [x] `exp`와 `iat`를 Unix timestamp로 변환
- [x] 시크릿 키를 환경 변수로 관리
- [x] 시크릿 키 길이 확인 (최소 32바이트)
- [x] 토큰 생성 후 자체 검증

### Java 코드
- [x] JWT Decoder 설정 (HS256)
- [x] 시크릿 키를 환경 변수로 관리
- [x] Security Filter Chain 설정
- [x] 호환성 테스트 작성

### 통합 테스트
- [x] Python → Java 토큰 검증
- [x] Java → Python 토큰 검증 (선택)
- [x] 실제 API 호출 테스트

---

**수정된 Python 코드를 사용하면 문제없이 작동합니다!** ✅
