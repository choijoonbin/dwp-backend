# Gateway 통합 테스트 가이드

DWP Backend의 Gateway와 Aura-Platform 통합 테스트 가이드입니다.

## 테스트 구조

### 1. AuraPlatformIntegrationTest
Aura-Platform과 Gateway 간의 통합을 검증하는 테스트입니다.

**테스트 케이스:**
- ✅ Gateway를 통한 Aura-Platform 헬스체크 접근
- ✅ Gateway를 통한 Aura-Platform 정보 조회
- ✅ SSE 타임아웃 설정 확인 (60초 이상)
- ✅ Gateway 라우팅 경로 검증 (StripPrefix 동작)
- ✅ CORS 헤더 확인
- ✅ 에러 응답 처리

### 2. GatewayRoutingTest
모든 서비스에 대한 Gateway 라우팅을 검증하는 테스트입니다.

**테스트 케이스:**
- ✅ Aura-Platform 라우팅
- ✅ Main Service 라우팅
- ✅ Auth Server 라우팅
- ✅ Mail Service 라우팅
- ✅ Chat Service 라우팅
- ✅ Approval Service 라우팅
- ✅ 존재하지 않는 경로 처리

## 테스트 실행 방법

### 전제 조건
테스트를 실행하기 전에 다음 서비스들이 실행 중이어야 합니다:

```bash
# 1. Docker Compose 인프라 실행
docker-compose up -d

# 2. 각 서비스 실행
./gradlew :dwp-main-service:bootRun &
./gradlew :dwp-auth-server:bootRun &
./gradlew :services:mail-service:bootRun &
./gradlew :services:chat-service:bootRun &
./gradlew :services:approval-service:bootRun &

# 3. Aura-Platform 실행 (포트 8000)
# 예: Python FastAPI 서비스
cd aura-platform
uvicorn main:app --port 8000 &
```

### 테스트 실행

#### 전체 Gateway 테스트 실행
```bash
./gradlew :dwp-gateway:test
```

#### 특정 테스트 클래스 실행
```bash
# Aura-Platform 통합 테스트만 실행
./gradlew :dwp-gateway:test --tests "AuraPlatformIntegrationTest"

# Gateway 라우팅 테스트만 실행
./gradlew :dwp-gateway:test --tests "GatewayRoutingTest"
```

#### 특정 테스트 메서드 실행
```bash
./gradlew :dwp-gateway:test --tests "AuraPlatformIntegrationTest.testAuraPlatformHealthCheckThroughGateway"
```

### 테스트 결과 확인
```bash
# 테스트 리포트 확인
open dwp-gateway/build/reports/tests/test/index.html
```

## Mock 서버를 사용한 테스트

실제 Aura-Platform 서비스가 없는 환경에서 테스트하려면 Mock 서버를 사용할 수 있습니다.

### WireMock 사용 예제

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class AuraPlatformMockTest {
    
    @Autowired
    private WebTestClient webTestClient;
    
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(WireMockConfiguration.wireMockConfig().port(8000))
        .build();
    
    @BeforeEach
    void setUp() {
        // Aura-Platform Health Check Mock
        wireMock.stubFor(get(urlEqualTo("/health"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"OK\",\"service\":\"aura-platform\"}")));
    }
    
    @Test
    void testAuraPlatformHealthWithMock() {
        webTestClient
            .get()
            .uri("/api/aura/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("OK")
            .jsonPath("$.service").isEqualTo("aura-platform");
    }
}
```

### WireMock 의존성 추가
`dwp-gateway/build.gradle`에 추가:

```gradle
dependencies {
    // 기존 의존성...
    
    // WireMock for testing
    testImplementation 'org.wiremock:wiremock-standalone:3.3.1'
}
```

## CI/CD 통합

### GitHub Actions 예제
```yaml
name: Gateway Integration Tests

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  test:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_USER: dwp_user
          POSTGRES_PASSWORD: dwp_password
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
      
      redis:
        image: redis:7
        options: >-
          --health-cmd "redis-cli ping"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
    
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew
    
    - name: Start all services
      run: |
        ./gradlew :dwp-main-service:bootRun &
        ./gradlew :dwp-auth-server:bootRun &
        ./gradlew :services:mail-service:bootRun &
        ./gradlew :services:chat-service:bootRun &
        ./gradlew :services:approval-service:bootRun &
        sleep 30  # 서비스 시작 대기
    
    - name: Run Gateway Integration Tests
      run: ./gradlew :dwp-gateway:test
    
    - name: Upload test results
      if: always()
      uses: actions/upload-artifact@v3
      with:
        name: test-results
        path: dwp-gateway/build/reports/tests/
```

## 트러블슈팅

### 1. 타임아웃 에러
```
java.net.ConnectException: Connection refused
```

**해결 방법:**
- Aura-Platform 서비스가 포트 8000에서 실행 중인지 확인
- 방화벽 설정 확인
- `application-test.yml`의 URI 설정 확인

### 2. CORS 에러
```
Access to fetch at 'http://localhost:8080/api/aura/health' has been blocked by CORS policy
```

**해결 방법:**
- `application-test.yml`의 CORS 설정 확인
- `CorsConfig.java`의 설정 확인
- 브라우저 캐시 삭제

### 3. 라우팅 에러
```
404 Not Found
```

**해결 방법:**
- Gateway 라우팅 설정 확인 (`application.yml`)
- StripPrefix 설정 확인
- 대상 서비스의 실제 경로 확인

## 테스트 커버리지

### 현재 커버리지 목표
- **라우팅**: 100% (모든 서비스 경로)
- **에러 처리**: 80% (주요 에러 케이스)
- **CORS**: 100% (모든 Origin 조합)
- **SSE**: 80% (타임아웃 시나리오)

### 커버리지 확인
```bash
./gradlew :dwp-gateway:jacocoTestReport
open dwp-gateway/build/reports/jacoco/test/html/index.html
```

## 참고 자료
- [Spring Cloud Gateway 공식 문서](https://spring.io/projects/spring-cloud-gateway)
- [WebTestClient 가이드](https://docs.spring.io/spring-framework/docs/current/reference/html/testing.html#webtestclient)
- [WireMock 문서](https://wiremock.org/docs/)
