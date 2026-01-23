# C01: Core Bean 적용 현황 점검

## 점검 일시
2026-01-22

## 점검 목적
DWP Backend의 각 서비스가 `dwp-core` 공통 빈(GlobalExceptionHandler, FeignHeaderInterceptor, RedisConfig 등)을 올바르게 로드하는지 확인

## 점검 대상 서비스
1. dwp-auth-server (Port: 8001)
2. dwp-main-service (Port: 8081)
3. services/mail-service (Port: 8082)
4. services/chat-service (Port: 8083)
5. services/approval-service (Port: 8084)
6. dwp-gateway (Port: 8080) - WebFlux 기반, 별도 분석

---

## 점검 결과

### ✅ dwp-auth-server
**ComponentScan 설정:**
```java
@ComponentScan(basePackages = {"com.dwp.core", "com.dwp.services.auth"})
```

**상태:** ✅ **정상**
- `com.dwp.core` 패키지를 명시적으로 스캔
- GlobalExceptionHandler, FeignHeaderInterceptor, RedisConfig 모두 로드됨
- ApiResponse<T> Envelope 적용 확인

**검증 방법:**
- Application 시작 로그에서 "DWP Core Configuration Loaded" 마커 확인
- `/api/admin/users` 호출 시 ApiResponse 형식 응답 확인
- 존재하지 않는 엔드포인트 호출 시 GlobalExceptionHandler 동작 확인

---

### ❌ dwp-main-service
**ComponentScan 설정:**
```java
@SpringBootApplication  // 기본: com.dwp.services.main만 스캔
@EnableFeignClients(basePackages = "com.dwp.services.main")
@EnableAsync
```

**상태:** ❌ **Core Bean 미적용 위험**
- `com.dwp.core` 패키지 스캔 누락
- GlobalExceptionHandler 미적용 가능성
- FeignHeaderInterceptor 미적용 → **표준 헤더 전파 누락 위험**
- RedisConfig는 application.yml에 `spring.data.redis` 설정이 있어 자동 로드될 수 있으나, ObjectMapper는 불명확

**영향도:**
- **HIGH**: HITL/AgentTask API 응답이 ApiResponse<T> 형식이 아닐 수 있음
- **HIGH**: Aura-Platform 호출 시 X-Agent-ID, X-DWP-Caller-Type 등 헤더 누락 가능
- **MEDIUM**: 예외 발생 시 일관되지 않은 에러 응답

**조치 필요:**
- C03~C09에서 AutoConfiguration으로 자동 적용 예정

---

### ❌ services/mail-service
**ComponentScan 설정:**
```java
@SpringBootApplication  // 기본: com.dwp.services.mail만 스캔
@EnableFeignClients(basePackages = "com.dwp.services.mail")
```

**상태:** ❌ **Core Bean 미적용**
- `com.dwp.core` 패키지 스캔 누락
- GlobalExceptionHandler, FeignHeaderInterceptor 미적용
- RedisConfig 미사용 (application.yml에 Redis 설정 없음)

**영향도:**
- **MEDIUM**: Mail API 응답 형식 불일치 가능성
- **MEDIUM**: Feign으로 타 서비스 호출 시 헤더 전파 누락
- **LOW**: 현재 Mail 서비스가 단순하여 즉각적 문제는 없으나 확장 시 위험

**조치 필요:**
- C03~C09에서 AutoConfiguration으로 자동 적용 예정

---

### ❌ services/chat-service
**ComponentScan 설정:**
```java
@SpringBootApplication  // 기본: com.dwp.services.chat만 스캔
@EnableFeignClients(basePackages = "com.dwp.services.chat")
```

**상태:** ❌ **Core Bean 미적용**
- `com.dwp.core` 패키지 스캔 누락
- GlobalExceptionHandler, FeignHeaderInterceptor 미적용
- RedisConfig 미사용

**영향도:**
- **MEDIUM**: Chat API 응답 형식 불일치 가능성
- **MEDIUM**: Feign 헤더 전파 누락
- **LOW**: 현재 기능이 단순하여 즉각적 문제는 없으나 확장 시 위험

**조치 필요:**
- C03~C09에서 AutoConfiguration으로 자동 적용 예정

---

### ❌ services/approval-service
**ComponentScan 설정:**
```java
@SpringBootApplication  // 기본: com.dwp.services.approval만 스캔
@EnableFeignClients(basePackages = "com.dwp.services.approval")
```

**상태:** ❌ **Core Bean 미적용**
- `com.dwp.core` 패키지 스캔 누락
- GlobalExceptionHandler, FeignHeaderInterceptor 미적용
- RedisConfig 미사용

**영향도:**
- **HIGH**: Approval API는 비즈니스 크리티컬 → 응답 형식/헤더 계약 미준수 시 통합 이슈
- **HIGH**: Feign 헤더 전파 누락 → 권한 체크 우회 가능성
- **MEDIUM**: 에러 응답 불일치

**조치 필요:**
- C03~C09에서 AutoConfiguration으로 자동 적용 예정

---

### ℹ️ dwp-gateway (WebFlux 기반)
**특이사항:**
- Spring Cloud Gateway는 WebFlux 기반으로 동작
- `dwp-core`는 현재 Spring MVC 기반 (spring-boot-starter-web)
- Gateway는 별도의 GlobalExceptionHandler 필요 없음 (라우팅만 수행)
- FeignHeaderInterceptor는 Gateway에서 불필요 (Gateway 자체가 헤더 전파 담당)

**상태:** ✅ **정상** (Gateway는 Core 의존 불필요)
- Gateway는 `HeaderPropagationFilter` 등 자체 필터로 헤더 전파 처리
- ApiResponse는 downstream 서비스에서 생성하므로 Gateway는 passthrough만 수행

---

## 종합 분석

### 문제 요약
| 서비스 | Core 스캔 | GlobalExceptionHandler | FeignHeaderInterceptor | RedisConfig | 위험도 |
|--------|-----------|------------------------|------------------------|-------------|--------|
| auth-server | ✅ | ✅ | ✅ | ✅ | LOW |
| main-service | ❌ | ❌ | ❌ | ? | **HIGH** |
| mail-service | ❌ | ❌ | ❌ | ❌ | MEDIUM |
| chat-service | ❌ | ❌ | ❌ | ❌ | MEDIUM |
| approval-service | ❌ | ❌ | ❌ | ❌ | **HIGH** |
| gateway | N/A | N/A | N/A | N/A | LOW |

### 핵심 리스크
1. **API 응답 형식 불일치**: auth-server는 ApiResponse<T>, 나머지는 불명확
2. **헤더 전파 누락**: main/mail/chat/approval에서 Feign 호출 시 X-Agent-ID, X-DWP-Caller-Type 등 표준 헤더 누락
3. **예외 처리 불일치**: GlobalExceptionHandler 미적용 서비스는 Spring 기본 에러 응답 (Whitelabel Error Page 또는 기본 JSON)
4. **테넌트 격리 우회 가능성**: 헤더 전파 누락 → X-Tenant-ID 손실 → 다른 테넌트 데이터 접근 가능성

---

## 조치 계획

### 단기 (C01~C05)
1. ✅ **C01**: 현황 점검 완료 (본 문서)
2. **C02**: dwp-core를 Starter 형태로 구조 변경 (bootJar 비활성화, java-library 전환)
3. **C03**: AutoConfiguration 스캐폴딩 추가 (META-INF/spring/...)
4. **C04**: GlobalExceptionHandler, ApiResponse를 AutoConfig로 자동 로드
5. **C05**: FeignHeaderInterceptor에 누락 헤더(X-Agent-ID, X-DWP-Caller-Type) 추가

### 중기 (C06~C09)
6. **C06**: Feign Header Propagation 테스트 추가 (WireMock 기반)
7. **C07**: ObjectMapper 중복 제거 전략 확정 (Core default + @ConditionalOnMissingBean)
8. **C08**: RedisConfig 충돌 방지 (@ConditionalOnMissingBean)
9. **C09**: 각 서비스에서 수동 @ComponentScan 제거 + AutoConfig 검증

---

## 검증 로그 추가 계획

각 서비스 Application 클래스에 다음 로그 추가 예정:

```java
@PostConstruct
public void checkCoreConfigLoaded() {
    ApplicationContext ctx = applicationContext;
    
    log.info("=".repeat(80));
    log.info("DWP Service Started: {}", applicationName);
    log.info("Checking Core Configuration...");
    
    // GlobalExceptionHandler 체크
    boolean hasGlobalExceptionHandler = ctx.containsBean("globalExceptionHandler");
    log.info("  - GlobalExceptionHandler: {}", hasGlobalExceptionHandler ? "✅ LOADED" : "❌ MISSING");
    
    // FeignHeaderInterceptor 체크
    boolean hasFeignInterceptor = ctx.containsBean("feignHeaderInterceptor");
    log.info("  - FeignHeaderInterceptor: {}", hasFeignInterceptor ? "✅ LOADED" : "❌ MISSING");
    
    // ObjectMapper 체크
    boolean hasObjectMapper = ctx.containsBean("objectMapper");
    log.info("  - ObjectMapper: {}", hasObjectMapper ? "✅ LOADED" : "❌ MISSING");
    
    // RedisTemplate 체크
    boolean hasRedisTemplate = ctx.containsBean("redisTemplate");
    log.info("  - RedisTemplate: {}", hasRedisTemplate ? "✅ LOADED" : "⚠️ NOT REQUIRED (or MISSING)");
    
    log.info("=".repeat(80));
}
```

---

## 다음 단계
- [x] C01 완료: 현황 점검 문서 작성
- [ ] C02 진행: dwp-core Gradle 구조 변경
- [ ] C03 진행: AutoConfiguration 추가
- [ ] C04~C09: Core 빈 자동 적용 완성

---

## 작성자
- DWP Backend Optimization Task (C01)
- 작성일: 2026-01-22
