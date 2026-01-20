# Gateway 시작 오류 해결

## 문제

`dwp-gateway` 기동 시 다음 오류 발생:

```
Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway.

Action:
Please set spring.main.web-application-type=reactive or remove spring-boot-starter-web dependency.
```

## 원인

1. **Spring Cloud Gateway는 WebFlux(reactive) 기반**이어야 합니다.
2. **`dwp-core`의 `GlobalExceptionHandler`**가 Spring MVC 기반(`@RestControllerAdvice`)입니다.
3. `dwp-core`가 `spring-boot-starter-web`을 포함하고 있어 Spring MVC가 클래스패스에 포함됩니다.

## 해결 방법

### 1. `application.yml`에 reactive 설정 추가

```yaml
spring:
  main:
    web-application-type: reactive  # Gateway는 WebFlux 기반이므로 reactive로 설정
```

### 2. `GatewayApplication`에서 `GlobalExceptionHandler` 제외

```java
@SpringBootApplication
@ComponentScan(
    basePackages = {"com.dwp"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {GlobalExceptionHandler.class}
    )
)
public class GatewayApplication {
    // ...
}
```

### 3. `build.gradle`에서 `spring-boot-starter-web` 제외 (이미 적용됨)

```gradle
implementation(project(':dwp-core')) {
    exclude group: 'org.springframework.boot', module: 'spring-boot-starter-web'
}
```

## 검증

Gateway가 정상적으로 시작되면 다음 로그가 출력됩니다:

```
Netty started on port 8080 (http)
Started GatewayApplication in 1.941 seconds
```

## 참고사항

- Gateway는 **라우팅만** 담당하므로 `GlobalExceptionHandler`가 필요하지 않습니다.
- Gateway에서 예외 처리가 필요한 경우, WebFlux 기반의 `@ControllerAdvice`를 별도로 구현해야 합니다.
- `dwp-core`의 `ApiResponse`와 `ErrorCode`는 Gateway에서도 사용 가능합니다 (단순 POJO이므로).
