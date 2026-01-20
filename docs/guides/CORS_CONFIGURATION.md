# CORS 설정 가이드

## 개요

`dwp-gateway`의 CORS 설정은 환경 변수와 Spring Profile을 통해 유연하게 관리할 수 있습니다.

## 설정 구조

### 1. 기본 설정 (`application.yml`)

로컬 개발 환경을 위한 기본 설정입니다.

```yaml
cors:
  allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:4200}
  allowed-methods: ${CORS_ALLOWED_METHODS:GET,POST,PUT,DELETE,PATCH,OPTIONS}
  allowed-headers: ${CORS_ALLOWED_HEADERS:*}
  allow-credentials: ${CORS_ALLOW_CREDENTIALS:true}
  max-age: ${CORS_MAX_AGE:3600}
```

### 2. Profile별 설정

- **`application-dev.yml`**: 개발 환경 설정
- **`application-prod.yml`**: 운영 환경 설정

## 사용 방법

### 로컬 개발 환경 (기본)

기본 설정을 사용하면 `http://localhost:4200`이 자동으로 허용됩니다.

```bash
./gradlew :dwp-gateway:bootRun
```

### 환경 변수로 Origin 지정

단일 Origin:
```bash
export CORS_ALLOWED_ORIGINS=http://localhost:3000
./gradlew :dwp-gateway:bootRun
```

다중 Origin (콤마로 구분):
```bash
export CORS_ALLOWED_ORIGINS=http://localhost:4200,http://localhost:3000,https://staging.example.com
./gradlew :dwp-gateway:bootRun
```

### 개발 환경 Profile 사용

```bash
export SPRING_PROFILES_ACTIVE=dev
export CORS_ALLOWED_ORIGINS=http://localhost:4200,http://localhost:3000
./gradlew :dwp-gateway:bootRun
```

### 운영 환경 Profile 사용

```bash
export SPRING_PROFILES_ACTIVE=prod
export CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
./gradlew :dwp-gateway:bootRun
```

또는 JAR 실행 시:
```bash
java -jar dwp-gateway.jar \
  --spring.profiles.active=prod \
  --cors.allowed-origins=https://app.example.com,https://admin.example.com
```

## 환경 변수 목록

| 환경 변수 | 기본값 | 설명 |
|----------|--------|------|
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | 허용할 Origin 목록 (콤마로 구분) |
| `CORS_ALLOWED_METHODS` | `GET,POST,PUT,DELETE,PATCH,OPTIONS` | 허용할 HTTP 메서드 (콤마로 구분) |
| `CORS_ALLOWED_HEADERS` | `*` | 허용할 헤더 (`*`는 모든 헤더 허용) |
| `CORS_ALLOW_CREDENTIALS` | `true` | Credentials 허용 여부 |
| `CORS_MAX_AGE` | `3600` | Preflight 요청 캐시 시간 (초) |

## 구현 세부사항

### CorsConfig 클래스

`CorsConfig.java`에서 `CorsWebFilter` Bean을 생성하여 CORS를 처리합니다.

```java
@Configuration
public class CorsConfig {
    @Value("${cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;
    
    // ... 기타 설정
    
    @Bean
    public CorsWebFilter corsWebFilter() {
        // CORS 설정 로직
    }
}
```

### Spring Cloud Gateway 호환성

Spring Cloud Gateway는 WebFlux 기반이므로 `CorsWebFilter`를 사용합니다. 
이는 Servlet 기반의 `CorsConfigurationSource`와는 다릅니다.

## 테스트

### CORS 설정 확인

```bash
# OPTIONS 요청으로 Preflight 테스트
curl -X OPTIONS http://localhost:8080/api/main/health \
  -H "Origin: http://localhost:4200" \
  -H "Access-Control-Request-Method: GET" \
  -v

# 실제 요청 테스트
curl http://localhost:8080/api/main/health \
  -H "Origin: http://localhost:4200" \
  -v
```

### 예상 응답 헤더

```
Access-Control-Allow-Origin: http://localhost:4200
Access-Control-Allow-Methods: GET,POST,PUT,DELETE,PATCH,OPTIONS
Access-Control-Allow-Credentials: true
Access-Control-Max-Age: 3600
```

## 주의사항

1. **운영 환경**: `CORS_ALLOWED_ORIGINS`에 실제 운영 도메인만 포함하세요.
2. **Credentials**: `allow-credentials: true`일 때는 `allowed-origins`에 `*`를 사용할 수 없습니다.
3. **보안**: 운영 환경에서는 가능한 한 구체적인 Origin을 지정하세요.

## 문제 해결

### CORS 오류가 발생하는 경우

1. **환경 변수 확인**
   ```bash
   echo $CORS_ALLOWED_ORIGINS
   ```

2. **Profile 확인**
   ```bash
   echo $SPRING_PROFILES_ACTIVE
   ```

3. **로그 확인**
   Gateway 로그에서 CORS 관련 오류 메시지를 확인하세요.

4. **Origin 형식 확인**
   - `http://` 또는 `https://`로 시작해야 합니다.
   - 포트 번호가 포함되어야 합니다 (예: `http://localhost:4200`).

## 참고 자료

- [Spring Cloud Gateway CORS](https://docs.spring.io/spring-cloud-gateway/docs/current/reference/html/#cors-configuration)
- [MDN CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS)
