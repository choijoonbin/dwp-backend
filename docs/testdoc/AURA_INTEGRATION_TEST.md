# Aura-Platform 통합 테스트 가이드

## 개요

이 문서는 Gateway를 통한 Aura-Platform 연동 및 JWT 인증 검증 방법을 설명합니다.

## 사전 요구사항

1. **Gateway 실행 중** (포트 8080)
2. **Aura-Platform 실행 중** (포트 8000)
3. **Python 3.x** 및 `python-jose`, `python-dotenv` 패키지 설치

## 테스트 단계

### 1. JWT 토큰 생성

```bash
cd dwp-auth-server
python3 test_jwt_for_aura.py
```

또는 토큰만 추출:

```bash
cd dwp-auth-server
python3 test_jwt_for_aura.py --token-only
```

### 2. Gateway를 통한 헬스체크 (인증 없이)

```bash
curl -X GET http://localhost:8080/api/aura/agents/health
```

**예상 응답:**
- `200 OK`: Gateway 라우팅 정상, Aura-Platform 응답 정상
- `401 Unauthorized`: 인증 필요 (정상 동작)
- `502 Bad Gateway`: Aura-Platform이 실행 중이지 않음
- `503 Service Unavailable`: Gateway가 실행 중이지 않음

### 3. JWT 토큰을 포함한 인증 테스트

```bash
# JWT 토큰 생성
TOKEN=$(cd dwp-auth-server && python3 test_jwt_for_aura.py --token-only)

# 인증 헤더와 함께 요청
curl -X GET http://localhost:8080/api/aura/agents/health \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: tenant1" \
  -H "X-DWP-Source: FRONTEND"
```

**예상 응답:**
- `200 OK`: 인증 성공, Aura-Platform이 JWT 토큰을 정상적으로 검증
- `401 Unauthorized`: 인증 실패
  - JWT 토큰이 유효하지 않음
  - Gateway에서 Authorization 헤더가 전파되지 않음
  - Aura-Platform에서 JWT 검증 실패

### 4. 자동화된 테스트 스크립트 실행

```bash
./scripts/test_aura_integration.sh
```

이 스크립트는 다음을 자동으로 수행합니다:
1. JWT 토큰 생성
2. Gateway를 통한 헬스체크 테스트
3. JWT 토큰을 포함한 인증 테스트

## 문제 해결

### 401 Unauthorized 에러 발생 시

#### 1. Gateway 헤더 전파 확인

Gateway 로그에서 다음 메시지를 확인:

```
Routing to Aura-Platform: /api/aura/agents/health with headers: Authorization=present, X-Tenant-ID=tenant1, X-DWP-Source=FRONTEND
```

**헤더가 전파되지 않는 경우:**

`dwp-gateway/src/main/resources/application.yml`에서 다음을 확인:

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

#### 2. JWT 토큰 유효성 확인

```bash
# 토큰 생성 및 검증
cd dwp-auth-server
python3 test_jwt_for_aura.py
```

토큰이 정상적으로 생성되고 검증되는지 확인합니다.

#### 3. Aura-Platform JWT 검증 확인

Aura-Platform에서 다음을 확인:
- JWT Secret Key가 `dwp-auth-server`와 동일한지
- JWT 알고리즘이 `HS256`인지
- 토큰의 `exp` 클레임이 만료되지 않았는지

### 502 Bad Gateway 에러 발생 시

Aura-Platform이 실행 중인지 확인:

```bash
curl http://localhost:8000/health
```

또는:

```bash
curl http://localhost:8000/aura/agents/health
```

### 503 Service Unavailable 에러 발생 시

Gateway가 실행 중인지 확인:

```bash
curl http://localhost:8080/api/main/health
```

## Gateway 헤더 전파 필터

`HeaderPropagationFilter`는 Gateway에서 다운스트림 서비스로 헤더를 전파하는 것을 보장합니다.

**전파되는 헤더:**
- `Authorization`: JWT 토큰
- `X-Tenant-ID`: 테넌트 식별자
- `X-DWP-Source`: 요청 출처 (AURA, FRONTEND, INTERNAL, BATCH)
- `X-User-ID`: 사용자 식별자

**로깅:**
Gateway 로그에서 헤더 전파 여부를 확인할 수 있습니다:

```
Propagating Authorization header to downstream service
Routing to Aura-Platform: /api/aura/agents/health with headers: Authorization=present, X-Tenant-ID=tenant1, X-DWP-Source=FRONTEND
```

## 참고 문서

- [JWT 호환성 가이드](./JWT_COMPATIBILITY_GUIDE.md)
- [AI 에이전트 인프라](./AI_AGENT_INFRASTRUCTURE.md)
- [Gateway 라우팅 테스트](./GATEWAY_ROUTING_TEST.md)
