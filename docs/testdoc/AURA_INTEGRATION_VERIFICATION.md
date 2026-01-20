# Aura-Platform 통합 검증 결과

## 검증 일시
$(date)

## 1. Gateway 라우팅 확인

### 테스트 명령어
```bash
curl -X GET http://localhost:8080/api/aura/agents/health
```

### 결과
- ✅ Gateway가 정상적으로 실행 중 (포트 8080)
- ✅ Aura-Platform으로 라우팅 정상
- ⚠️ Aura-Platform이 인증을 요구함 (401 응답 - 정상 동작)

## 2. 헤더 전파 확인

### Gateway 설정
`dwp-gateway/src/main/resources/application.yml`:
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: aura-platform
          uri: ${AURA_PLATFORM_URI:http://localhost:8000}
          predicates:
            - Path=/api/aura/**
          filters:
            - StripPrefix=1
            - PreserveHostHeader  # 헤더 전파 보장
```

### HeaderPropagationFilter
`dwp-gateway/src/main/java/com/dwp/gateway/config/HeaderPropagationFilter.java`가 다음 헤더를 전파합니다:
- `Authorization`: JWT 토큰
- `X-Tenant-ID`: 테넌트 식별자
- `X-DWP-Source`: 요청 출처
- `X-User-ID`: 사용자 식별자

## 3. JWT 토큰 생성

### Python 패키지 설치
```bash
./scripts/setup_jwt_test.sh
```

또는 수동 설치:
```bash
pip install python-jose[cryptography] python-dotenv
```

### 토큰 생성
```bash
cd dwp-auth-server
python3 test_jwt_for_aura.py
```

또는 토큰만 추출:
```bash
cd dwp-auth-server
python3 test_jwt_for_aura.py --token-only
```

## 4. 인증 테스트

### JWT 토큰을 포함한 요청
```bash
TOKEN="<생성된_JWT_토큰>"

curl -X GET http://localhost:8080/api/aura/agents/health \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-DWP-Source: FRONTEND"
```

### 예상 결과
- `200 OK`: 인증 성공, Aura-Platform이 JWT 토큰을 정상적으로 검증
- `401 Unauthorized`: 인증 실패
  - JWT 토큰이 유효하지 않음
  - Gateway에서 Authorization 헤더가 전파되지 않음
  - Aura-Platform에서 JWT 검증 실패

## 5. 문제 해결 체크리스트

### 401 에러 발생 시

1. **Gateway 헤더 전파 확인**
   - Gateway 로그에서 `HeaderPropagationFilter` 메시지 확인
   - `Routing to Aura-Platform: ... with headers: Authorization=present` 확인

2. **JWT 토큰 유효성 확인**
   - 토큰이 정상적으로 생성되는지 확인
   - 토큰의 `exp` 클레임이 만료되지 않았는지 확인

3. **Aura-Platform JWT 검증 확인**
   - Aura-Platform의 JWT Secret Key가 `dwp-auth-server`와 동일한지 확인
   - JWT 알고리즘이 `HS256`인지 확인

4. **네트워크 확인**
   - Gateway → Aura-Platform 연결 확인
   - 포트 8000이 열려있는지 확인

## 6. 자동화된 테스트

### 테스트 스크립트 실행
```bash
./scripts/test_aura_integration.sh
```

이 스크립트는 다음을 자동으로 수행합니다:
1. JWT 토큰 생성
2. Gateway를 통한 헬스체크 테스트
3. JWT 토큰을 포함한 인증 테스트

## 7. Gateway 로그 확인

Gateway 로그에서 다음 메시지를 확인할 수 있습니다:

```
Propagating Authorization header to downstream service
Routing to Aura-Platform: /api/aura/agents/health with headers: Authorization=present, X-Tenant-ID=tenant1, X-DWP-Source=FRONTEND
```

## 다음 단계

1. JWT 토큰 생성 및 인증 테스트 수행
2. Aura-Platform에서 JWT 검증 로직 확인
3. 실제 비즈니스 로직 연동 테스트
