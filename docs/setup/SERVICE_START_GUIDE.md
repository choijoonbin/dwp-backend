# 백엔드 서비스 실행 가이드

## 현재 상황

Docker Compose는 성공적으로 실행되었습니다:
- ✅ PostgreSQL (포트 5432)
- ✅ Redis (포트 6379)

하지만 Gradle 빌드 중 SSL 인증서 문제로 인해 서비스 실행이 지연되고 있습니다.

## 서비스 실행 방법

### 방법 1: IDE에서 실행 (권장)

각 서비스의 메인 클래스를 IDE에서 직접 실행하세요:

1. **dwp-auth-server**
   - 메인 클래스: `com.dwp.services.auth.AuthServerApplication`
   - 포트: 8000

2. **dwp-main-service**
   - 메인 클래스: `com.dwp.services.main.MainServiceApplication`
   - 포트: 8081

3. **services/mail-service**
   - 메인 클래스: `com.dwp.services.mail.MailServiceApplication`
   - 포트: 8082

4. **services/chat-service**
   - 메인 클래스: `com.dwp.services.chat.ChatServiceApplication`
   - 포트: 8083

5. **services/approval-service**
   - 메인 클래스: `com.dwp.services.approval.ApprovalServiceApplication`
   - 포트: 8084

6. **dwp-gateway** (마지막에 실행)
   - 메인 클래스: `com.dwp.gateway.GatewayApplication`
   - 포트: 8080

### 방법 2: 터미널에서 개별 실행

각 서비스를 별도의 터미널 창에서 실행하세요:

```bash
# 터미널 1: Auth Server
cd /Users/joonbinchoi/Work/dwp/dwp-backend
./gradlew :dwp-auth-server:bootRun

# 터미널 2: Main Service
cd /Users/joonbinchoi/Work/dwp/dwp-backend
./gradlew :dwp-main-service:bootRun

# 터미널 3: Mail Service
cd /Users/joonbinchoi/Work/dwp/dwp-backend
./gradlew :services:mail-service:bootRun

# 터미널 4: Chat Service
cd /Users/joonbinchoi/Work/dwp/dwp-backend
./gradlew :services:chat-service:bootRun

# 터미널 5: Approval Service
cd /Users/joonbinchoi/Work/dwp/dwp-backend
./gradlew :services:approval-service:bootRun

# 터미널 6: Gateway (마지막에 실행)
cd /Users/joonbinchoi/Work/dwp/dwp-backend
./gradlew :dwp-gateway:bootRun
```

### 방법 3: SSL 인증서 문제 해결 후 실행

SSL 인증서 문제를 해결하려면:

1. **Gradle 설정 확인**
   ```bash
   # gradle.properties 파일 생성 또는 수정
   systemProp.javax.net.ssl.trustStore=/path/to/truststore
   ```

2. **네트워크 프록시 설정 확인**
   - 회사 네트워크나 프록시 환경인 경우 설정이 필요할 수 있습니다.

3. **Gradle 캐시 초기화**
   ```bash
   rm -rf ~/.gradle/caches
   ./gradlew clean build
   ```

## 서비스 실행 순서

1. **Docker Compose 실행** (이미 완료)
   ```bash
   docker-compose up -d
   ```

2. **서비스 실행 순서**
   - Auth Server (8000)
   - Main Service (8081)
   - Mail Service (8082)
   - Chat Service (8083)
   - Approval Service (8084)
   - Gateway (8080) - **마지막에 실행**

## 서비스 상태 확인

### 포트 확인
```bash
lsof -i :8000  # Auth Server
lsof -i :8080  # Gateway
lsof -i :8081  # Main Service
lsof -i :8082  # Mail Service
lsof -i :8083  # Chat Service
lsof -i :8084  # Approval Service
```

### 헬스 체크
```bash
# Gateway를 통한 접근
curl http://localhost:8080/api/main/health
curl http://localhost:8080/api/auth/health
curl http://localhost:8080/api/mail/health
curl http://localhost:8080/api/chat/health
curl http://localhost:8080/api/approval/health

# 직접 접근
curl http://localhost:8000/auth/health
curl http://localhost:8081/main/health
curl http://localhost:8082/mail/health
curl http://localhost:8083/chat/health
curl http://localhost:8084/approval/health
```

## 문제 해결

### Gateway 시작 오류

**증상**: `Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway`

**해결**: 이미 수정 완료되었습니다. 다음 설정이 적용되어 있습니다:
- `application.yml`에 `spring.main.web-application-type=reactive` 설정
- `GatewayApplication`에서 `GlobalExceptionHandler` 제외

자세한 내용은 [GATEWAY_FIX.md](GATEWAY_FIX.md)를 참조하세요.

### 서비스가 시작되지 않는 경우

1. **포트 충돌 확인**
   ```bash
   lsof -i :8000 -i :8080 -i :8081 -i :8082 -i :8083 -i :8084
   ```

2. **데이터베이스 연결 확인**
   ```bash
   docker-compose ps
   docker-compose logs postgres
   ```

3. **로그 확인**
   - IDE 콘솔 또는 터미널 출력 확인
   - 각 서비스의 application.yml 설정 확인

### 의존성 다운로드 실패

1. **Gradle 캐시 삭제**
   ```bash
   rm -rf ~/.gradle/caches
   ```

2. **오프라인 모드 확인**
   - 네트워크 연결 확인
   - 프록시 설정 확인

3. **Maven Central 접근 확인**
   ```bash
   curl https://repo1.maven.org/maven2/
   ```
