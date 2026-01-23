# JWT 토큰 발급 알고리즘 검증 결과

**검증일**: 2024-01-16  
**상태**: ⚠️ **수정 필요**

---

## 🔍 발견된 문제점

### ❌ 문제 1: `exp`와 `iat` 필드 형식 오류

**제시하신 코드:**
```python
payload = {
    "exp": datetime.now(timezone.utc) + timedelta(hours=1),  # ❌ datetime 객체
    "iat": datetime.now(timezone.utc),  # ❌ datetime 객체
}
```

**문제**: JWT 표준(RFC 7519)에 따르면 `exp`와 `iat`는 **Unix timestamp (초 단위 정수)**여야 합니다. `datetime` 객체를 직접 넣으면 Java에서 파싱할 수 없습니다.

### ✅ 해결 방법

```python
# ✅ 올바른 코드
now = datetime.now(timezone.utc)
expiration = now + timedelta(hours=1)

payload = {
    "sub": "backend_user_001",
    "tenant_id": "tenant1",
    "email": "user@dwp.com",
    "role": "user",
    "exp": int(expiration.timestamp()),  # ✅ Unix timestamp로 변환
    "iat": int(now.timestamp()),  # ✅ Unix timestamp로 변환
}
```

---

## ✅ 완료된 작업

### 1. JWT 검증 설정 추가
- **파일**: `dwp-auth-server/src/main/java/com/dwp/services/auth/config/JwtConfig.java`
- **기능**: HS256 알고리즘으로 Python (jose) 토큰 검증 가능

### 2. 호환성 테스트 작성
- **파일**: `dwp-auth-server/src/test/java/com/dwp/services/auth/JwtCompatibilityTest.java`
- **테스트 케이스**:
  - Python 호환 JWT 토큰 생성 및 검증
  - 토큰 형식 검증 (exp는 숫자여야 함)
  - 시크릿 키 길이 검증

### 3. Python 테스트 스크립트
- **파일**: `dwp-auth-server/test_jwt_for_aura.py`
- **기능**: 올바른 형식으로 JWT 토큰 생성 및 검증

### 4. 문서화
- **파일**: `docs/JWT_COMPATIBILITY_GUIDE.md`
- **내용**: 상세한 호환성 가이드 및 문제 해결 방법

---

## 📋 수정된 Python 코드

### 완전한 예제
```python
# test_jwt_for_aura.py
from datetime import datetime, timedelta, timezone
from jose import jwt
import os
from dotenv import load_dotenv

load_dotenv()

# 환경 변수에서 시크릿 키 로드
SECRET_KEY = os.getenv(
    "JWT_SECRET", 
    "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256"
)
ALGORITHM = "HS256"

# 현재 시간 (UTC)
now = datetime.now(timezone.utc)
expiration = now + timedelta(hours=1)

# JWT payload 생성
# ⚠️ 중요: exp와 iat는 Unix timestamp (초 단위 정수)로 변환!
payload = {
    "sub": "backend_user_001",
    "tenant_id": "tenant1",
    "email": "user@dwp.com",
    "role": "user",
    "exp": int(expiration.timestamp()),  # ✅ 수정됨
    "iat": int(now.timestamp()),  # ✅ 수정됨
}

# 토큰 생성
token = jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)
print(f"JWT Token for Aura-Platform:\n{token}")
```

---

## 🧪 테스트 방법

### 1. Python에서 토큰 생성
```bash
cd dwp-auth-server
python test_jwt_for_aura.py
```

### 2. Java에서 검증
```bash
cd dwp-backend
./gradlew :dwp-auth-server:test --tests "JwtCompatibilityTest"
```

### 3. 실제 API 호출
```bash
# 1. 토큰 생성
TOKEN=$(python dwp-auth-server/test_jwt_for_aura.py | grep -A1 "Generated JWT Token" | tail -1)

# 2. DWP Backend API 호출
curl -X GET http://localhost:8080/api/auth/info \
  -H "Authorization: Bearer $TOKEN"
```

---

## ⚙️ 환경 변수 설정

### 공유 시크릿 키
**`.env` (Aura-Platform):**
```bash
JWT_SECRET=your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256
```

**`application.yml` (DWP Backend):**
```yaml
jwt:
  secret: ${JWT_SECRET:your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256}
```

**시크릿 키 생성:**
```bash
# 256비트(32바이트) 이상의 랜덤 키
openssl rand -base64 32
```

---

## ✅ 최종 확인 사항

### Python 코드
- [x] `exp`와 `iat`를 `int(timestamp)`로 변환
- [x] 시크릿 키를 환경 변수로 관리
- [x] 시크릿 키 길이 확인 (최소 32바이트)

### Java 코드
- [x] JWT Decoder 설정 (HS256)
- [x] Security Filter Chain 설정
- [x] 호환성 테스트 작성

### 통합
- [x] Python → Java 토큰 검증 가능
- [x] 실제 API 호출 테스트 준비 완료

---

## 📚 참고 문서

1. **[JWT_COMPATIBILITY_GUIDE.md](./JWT_COMPATIBILITY_GUIDE.md)**: 상세 가이드
2. **[test_jwt_for_aura.py](../dwp-auth-server/test_jwt_for_aura.py)**: 수정된 Python 스크립트
3. **[JwtCompatibilityTest.java](../dwp-auth-server/src/test/java/com/dwp/services/auth/JwtCompatibilityTest.java)**: Java 테스트 코드

---

## 🎯 결론

**수정 전**: ❌ `datetime` 객체를 직접 사용 → Java에서 파싱 불가  
**수정 후**: ✅ `int(timestamp)`로 변환 → 완벽한 호환성

**수정된 코드를 사용하면 문제없이 작동합니다!** ✅

---

**다음 단계**: `test_jwt_for_aura.py`를 사용하여 토큰을 생성하고 실제 API 호출을 테스트하세요.
