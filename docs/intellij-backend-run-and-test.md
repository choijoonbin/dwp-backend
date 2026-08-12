# IntelliJ 백엔드 실행 및 테스트 가이드

이 문서는 로컬 인프라는 Docker로 실행하고 Java 서비스는 IntelliJ에서 실행·디버깅하는
표준 절차를 정의합니다. 전체 스택을 한 번에 실행할 때는 `./dev up full`을 사용합니다.

- 상태: Active
- 최종 검증일: 2026-08-12
- 기준 소스: `settings.gradle`, `build.gradle`, `scripts/devctl.py`, 각 서비스의
  `application.yml`

## 1. 사전 조건

- IntelliJ Project SDK: JDK 17
- Gradle JVM: JDK 17
- Gradle 배포 방식: Wrapper (`gradle-wrapper.properties`의 Gradle 8.11.1)
- Docker Desktop 실행
- 프로젝트 루트: `dwp-backend/`

IntelliJ에서 `dwp-backend/settings.gradle`을 Gradle 프로젝트로 불러온 뒤 Gradle Sync를
완료합니다. 다른 JDK로 애플리케이션이 실행되더라도 CI와 동일한 결과를 위해 Project SDK,
Gradle JVM, Run Configuration의 JRE를 모두 17로 맞춥니다.

## 2. 실행 방식 선택

같은 포트에서 Supervisor 프로세스와 IntelliJ 프로세스를 동시에 실행할 수 없습니다.
IntelliJ에서 디버깅하기 전 다음 순서로 애플리케이션만 중지하고 인프라를 준비합니다.

```bash
cd dwp-backend
./dev stop
docker compose up -d postgres redis
```

`./dev stop`은 관리 중인 애플리케이션 프로세스를 중지하지만 Docker 데이터 볼륨은
삭제하지 않습니다. `./dev reset --yes`는 데이터베이스와 Redis 데이터를 삭제하므로
명시적인 초기화가 필요한 경우에만 사용합니다.

## 3. IntelliJ Run Configuration

각 서비스에 `Spring Boot` Run Configuration을 만들고 다음 값을 사용합니다. Spring Boot
구성을 사용할 수 없는 IntelliJ 에디션에서는 같은 Main class를 사용하는 `Application`
구성을 만듭니다.

| 이름 | Main class | Use classpath of module | Port |
| --- | --- | --- | ---: |
| `AuthServerApplication` | `com.dwp.services.auth.AuthServerApplication` | `dwp-backend.dwp-auth-server.main` | 8001 |
| `PlatformServerApplication` | `com.dwp.services.platform.PlatformServerApplication` | `dwp-backend.dwp-platform-server.main` | 8002 |
| `PeopleServerApplication` | `com.dwp.services.people.PeopleServerApplication` | `dwp-backend.dwp-people-server.main` | 8003 |
| `ProviderServerApplication` | `com.dwp.services.provider.ProviderServerApplication` | `dwp-backend.dwp-provider-server.main` | 8004 |
| `GatewayApplication` | `com.dwp.gateway.GatewayApplication` | `dwp-backend.dwp-gateway.main` | 8080 |

공통 설정은 다음과 같습니다.

- Working directory: `$PROJECT_DIR$`
- JRE: Project SDK 17
- Active profiles: 비워 둠
- Build and run using: Gradle

특히 Auth의 classpath를 `dwp-core.main`으로 지정하면 실행할 Main class를 찾지 못합니다.
현재 `settings.gradle`에 없는 `McpServerApplication`, `SynapsexServiceApplication` 구성은
이 백엔드의 유효한 실행 구성이 아니므로 로컬 Run Configuration에서 제거합니다.

## 4. 로컬 환경 변수

DB와 Redis의 기본 접속값은 `application.yml`에도 로컬 기본값이 있지만, IntelliJ와
`./dev`의 동작을 일치시키려면 아래 공통 값을 Run Configuration에 설정합니다.

```text
DB_HOST=localhost;DB_PORT=5432;DB_USERNAME=dwp_user;DB_PASSWORD=dwp_password;REDIS_HOST=localhost;REDIS_PORT=6379;REDIS_PASSWORD=dwp_redis_password;DWP_ENVIRONMENT=local
```

서비스 간 연동, 감사 수집, API 이력, Provider 지원 세션까지 검증할 때는
`scripts/devctl.py`의 `local_environment()`와 `service_environment()`가 유일한 로컬
기준값입니다. 특히 다음 값은 해당 기능을 실행할 때 누락하면 안 됩니다.

| 대상 | 필수 로컬 설정 |
| --- | --- |
| Gateway | `SERVICE_AUTH_URL`, `SERVICE_PLATFORM_URL`, `SERVICE_PEOPLE_URL`, `SERVICE_PROVIDER_URL`, 각 서비스 Token |
| Platform | `DWP_PLATFORM_SERVICE_TOKEN`, `DWP_PLATFORM_RUNTIME_SERVICE_TOKEN`, 감사·API 이력 수집 설정 |
| People | `DWP_PEOPLE_SERVICE_TOKEN`, `DWP_PEOPLE_CURSOR_SECRET`, Identity Sync 설정 |
| Provider | `DWP_PROVIDER_SERVICE_TOKEN`, `DWP_PROVIDER_PROVISIONING_TOKEN`, `DWP_PROVIDER_SUPPORT_VALIDATION_TOKEN`, `DWP_PROVIDER_SUPPORT_COOKIE_SECURE=false` |
| Auth | Redis 설정, `DWP_PROVIDER_PROVISIONING_TOKEN`, Identity Sync 설정 |

로컬 비밀값은 개발 전용입니다. 운영 환경에서는 문서의 값을 재사용하지 않고 Secret
Store에서 서비스별 독립 값을 주입합니다.

## 5. 실행 순서

다음 순서로 실행하면 의존 서비스 오류를 가장 쉽게 구분할 수 있습니다.

1. `AuthServerApplication`
2. `PlatformServerApplication`
3. `PeopleServerApplication`
4. `ProviderServerApplication`
5. `GatewayApplication`

모두 한 번에 실행하려면 위 구성을 포함한 IntelliJ Compound Configuration을 추가할 수
있습니다. Agent는 Java 모듈이 아니라 인접 저장소 `../dwp_agent`의 Python 서비스이며
전체 AI 기능을 검증할 때 별도로 8010 포트에서 실행합니다.

## 6. 기동 검증

각 실행 콘솔에서 `Started ...Application`을 확인한 뒤 다음 Health Endpoint를 점검합니다.

```bash
curl -fsS http://localhost:8001/actuator/health
curl -fsS http://localhost:8002/actuator/health
curl -fsS http://localhost:8003/actuator/health
curl -fsS http://localhost:8004/actuator/health
curl -fsS http://localhost:8080/actuator/health
```

정상 응답은 HTTP 200과 `"status":"UP"`을 포함합니다. Frontend를 함께 검증할 때는
Gateway 8080을 통해 요청하고, 서비스 포트 직접 호출은 장애 분리 목적으로만 사용합니다.

## 7. 테스트 실행

전체 백엔드 테스트:

```bash
./gradlew clean test --no-daemon
```

서비스별 테스트:

```bash
./gradlew :dwp-auth-server:test --no-daemon
./gradlew :dwp-platform-server:test --no-daemon
./gradlew :dwp-people-server:test --no-daemon
./gradlew :dwp-provider-server:test --no-daemon
./gradlew :dwp-gateway:test --no-daemon
```

IntelliJ에서는 Gradle Test Configuration을 사용해야 CLI 및 CI와 같은 Gradle test
task, JDK, 의존성 해석을 적용할 수 있습니다.

## 8. 자주 발생하는 문제

| 증상 | 확인 및 조치 |
| --- | --- |
| Main class를 찾지 못함 | Main class와 `Use classpath of module` 조합을 위 표와 비교하고 Gradle Sync 수행 |
| `Address already in use` | `./dev stop` 실행 후 해당 포트를 점유한 이전 Java 프로세스 종료 |
| PostgreSQL/Redis 연결 실패 | Docker Desktop, `docker compose ps`, 5432·6379 포트와 계정값 확인 |
| Flyway 검증 실패 | 임의로 Migration을 수정하지 말고 실패한 버전과 DB 상태 확인. 초기화는 로컬 데이터 삭제 승인을 받은 경우만 수행 |
| 서비스 간 401/403 | `scripts/devctl.py` 기준의 서비스 Token이 호출자와 수신자 양쪽에 동일한지 확인 |
| Provider 지원 세션이 유지되지 않음 | 로컬 HTTP에서는 `DWP_PROVIDER_SUPPORT_COOKIE_SECURE=false` 확인 |
| Gateway는 UP이나 일부 메뉴가 실패 | Auth·Platform·People·Provider Health와 Gateway route 대상 URL을 각각 확인 |

`.idea/workspace.xml`은 사용자별 상태 파일이므로 실행 구성의 기준 문서로 사용하지 않습니다.
모듈 추가·삭제 또는 포트 변경 시 이 문서, 루트 `README.md`, `scripts/devctl.py`를 함께
갱신합니다.
