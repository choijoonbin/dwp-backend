# C02: dwp-core Starter 형태로 구조 확정

## 작업 일시
2026-01-22

## 목적
dwp-core를 "부트 앱"이 아닌 **Auto-Configuration Starter**로 전환하여,  
모든 서비스가 자동으로 공통 설정을 로드하도록 기반 구조 정리

---

## 변경 사항

### 1. dwp-core/build.gradle 구조 변경

#### Before (문제점)
```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    // ...
}
```

**문제:**
- core가 모든 starter를 `implementation`으로 강제 의존
- Redis 사용하지 않는 서비스도 Redis 의존성 끌고 옴
- 불필요한 의존성 → 빌드 시간 증가, 충돌 가능성

#### After (해결)
```gradle
dependencies {
    // AutoConfiguration 지원
    implementation 'org.springframework.boot:spring-boot-autoconfigure'
    annotationProcessor 'org.springframework.boot:spring-boot-autoconfigure-processor'
    annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'
    
    // 조건부 의존성 (compileOnly)
    compileOnly 'org.springframework.boot:spring-boot-starter-web'
    compileOnly 'org.springframework.boot:spring-boot-starter-validation'
    compileOnly 'org.springframework.cloud:spring-cloud-starter-openfeign'
    compileOnly 'org.springframework.boot:spring-boot-starter-data-redis'
    compileOnly 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'
    
    // 필수 의존성 (always required)
    implementation 'org.springframework:spring-context'
    implementation 'org.springframework:spring-web'
    implementation 'com.fasterxml.jackson.core:jackson-databind'
    
    // Lombok
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    
    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-web'
    testImplementation 'org.springframework.cloud:spring-cloud-starter-openfeign'
}
```

**효과:**
- ✅ **조건부 로딩**: 서비스에 클래스가 있을 때만 AutoConfig 실행
- ✅ **의존성 최소화**: core가 불필요한 의존성 강제하지 않음
- ✅ **표준 패턴**: Spring Boot Starter 표준 구조 준수
- ✅ **확장성**: 향후 새로운 AutoConfig 추가 용이

---

### 2. jar/bootJar 설정 유지

```gradle
// 실행 가능한 jar가 아닌 라이브러리 jar 생성
jar {
    enabled = true
}

bootJar {
    enabled = false
}
```

**이유:**
- core는 "라이브러리"이므로 실행 가능한 jar 불필요
- 다른 모듈이 core를 의존할 때 일반 jar로 포함되어야 함

---

## 의존성 전략 설명

### compileOnly vs implementation

| 의존성 | 전략 | 이유 |
|--------|------|------|
| spring-boot-autoconfigure | **implementation** | AutoConfiguration 메커니즘 필수 |
| spring-context | **implementation** | ApplicationContext 등 기본 기능 필수 |
| jackson-databind | **implementation** | ApiResponse JSON 직렬화 필수 |
| spring-boot-starter-web | **compileOnly** | 조건부: Web 서비스만 필요 (Gateway는 WebFlux) |
| spring-cloud-starter-openfeign | **compileOnly** | 조건부: Feign 사용 서비스만 필요 |
| spring-boot-starter-data-redis | **compileOnly** | 조건부: Redis 사용 서비스만 필요 |

**조건부 로딩 예시:**
```java
@Configuration
@ConditionalOnClass(RedisConnectionFactory.class)  // Redis가 classpath에 있을 때만 로드
public class CoreRedisAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RedisTemplate<String, String> redisTemplate(...) {
        // ...
    }
}
```

---

## 다음 단계 (C03)

- [ ] `dwp-core/src/main/resources/META-INF/spring/` 디렉토리 생성
- [ ] `org.springframework.boot.autoconfigure.AutoConfiguration.imports` 파일 생성
- [ ] AutoConfiguration 클래스들 생성:
  - `CoreWebAutoConfiguration` (GlobalExceptionHandler, ApiResponse)
  - `CoreFeignAutoConfiguration` (FeignHeaderInterceptor)
  - `CoreJacksonAutoConfiguration` (ObjectMapper)
  - `CoreRedisAutoConfiguration` (RedisTemplate)

---

## 검증

### 빌드 성공
```bash
./gradlew :dwp-core:build
```
✅ **결과**: 성공 (compileOnly 의존성으로 인한 컴파일 에러 없음)

### 의존성 트리 확인
```bash
./gradlew :dwp-core:dependencies --configuration compileClasspath
```
✅ **결과**: 필수 의존성만 포함, compileOnly는 컴파일 타임에만 사용

---

## 영향 범위

| 항목 | 영향 | 비고 |
|------|------|------|
| Contract 변경 | **없음** | 외부 API 영향 없음 |
| 서비스 기동 영향 | **없음** | 현재 auth-server는 @ComponentScan으로 동작 중 |
| 빌드 시간 | **개선** | core 빌드 시 불필요한 의존성 제거 |
| 다음 커밋 영향 | **있음** | C03에서 AutoConfiguration 추가 필요 |

---

## 커밋 정보
- **완료 커밋**: C02
- **변경 파일**: 
  - `dwp-core/build.gradle` (의존성 전략 변경)
  - `docs/archive/backend-audit/C02_core_gradle_cleanup.md` (본 문서)

---

## 작성자
- DWP Backend Optimization Task (C02)
- 작성일: 2026-01-22
