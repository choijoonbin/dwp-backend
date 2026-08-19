# DWP Backend Starter

새 DWP 프로젝트를 시작하기 위한 공통 백엔드 기반입니다. 인증, 테넌트, RBAC,
요청 추적과 Platform Control Plane을 포함하며 기존 업무 도메인 API와 데이터는
포함하지 않습니다.

## Modules

| Module                   | Port | Responsibility                                                    |
| ------------------------ | ---: | ----------------------------------------------------------------- |
| `dwp-audit`              |    - | 감사 이벤트 계약과 영속 어댑터                                   |
| `dwp-core`               |    - | API 응답, 예외, 공통 헤더와 요청 추적                             |
| `dwp-observability`      |    - | API 이력 수집과 민감정보 최소화                                   |
| `dwp-platform-contracts` |    - | Provider 중립 Connector·Search·Workflow·Agent·Audit Port 계약     |
| `dwp-auth-server`        | 8001 | 로컬/OIDC 로그인, Browser Session, 테넌트, 사용자, RBAC            |
| `dwp-platform-server`    | 8002 | Tenant 기준정보, 제품 Registry, Lifecycle과 관리 Audit             |
| `dwp-people-server`      | 8003 | HRIS 연계용 Workforce Projection, 발령 이력과 People Audit 기반    |
| `dwp-provider-server`    | 8004 | Provider 조직, 테넌트, 구독, 권한과 프로비저닝 Control Plane      |
| `dwp-approval-server`    | 8005 | 전자결재 기안, 검토, 위임, 정책과 감사 가능한 결정 처리          |
| `dwp-space-server`       | 8006 | 구성원 Space, 소유자 운영, 템플릿·콘텐츠·수명주기 거버넌스       |
| `dwp-gateway`            | 8080 | 단일 API 진입점, Session 재검증, 내부 Identity Relay, CSRF와 CORS |

인증 서버는 `dwp_auth`, Platform Server는 `dwp_platform`, People Server는
`dwp_people`, Provider Server는 `dwp_provider`, Approval Server는 `dwp_approval`, Space Server는 `dwp_space`
Database를 각각 소유합니다.
Redis는 Auth의 만료형 OIDC state, nonce와 PKCE verifier를 저장합니다.
`dwp_people`은 외부 HRIS를 대체하지 않고
사람·근로관계·유효일 발령·조직·프로필의 DWP 운영 Projection만 보관합니다.
인증 스키마는 인증/RBAC, Group Role, 직무분리, 서버 측 Session 폐기와 Identity 변경
감사를 소유합니다. Tenant Admin의 Role 변경은 자기 권한 변경과 마지막 Admin 제거를
차단하고, 대상 사용자의 Active Session을 폐기하며 전후 Snapshot을 기록합니다.
Platform 스키마는 기준정보, 메뉴·다국어, 제품 Registry, 관리 명령 승인과 Append-only
Audit을 소유합니다. `sys_auth_sessions`는 JWT 원문이 아니라 `jti`, 사용자, 만료·폐기와
발급 Context만 저장합니다.

`dwp-platform-contracts`는 Java Port와 Value Contract만 포함하며 Table, Connector SDK,
Search Engine, Workflow Runtime과 Model Dependency를 추가하지 않습니다.

Agent와 Platform 경로는 Browser Session을 검증한 뒤 내부 Identity Header와 각
Service Token을 Gateway가 새로 주입합니다. 외부에서 전달한 동일 Header는 항상
제거됩니다. 기본 Token은 로컬 Supervisor에만 있으며 운영 환경에서는 Secret Store의
독립 값과 내부 Network 또는 mTLS를 사용해야 합니다.

Agent가 Platform Catalog를 해석할 때는 관리자용 `DWP_PLATFORM_SERVICE_TOKEN`이 아니라
`DWP_PLATFORM_RUNTIME_SERVICE_TOKEN`을 사용합니다. 이 Token은 Runtime Read Route만
허용되며 Platform Admin API에는 사용할 수 없습니다.

브라우저 Access Token은 응답 본문에 노출하지 않고 `HttpOnly` Cookie로
발급합니다. Local Development의 기본 Cookie는 HTTP를 위해 `Secure=false`이며,
배포 환경에서는 반드시 다음 값을 적용해야 합니다.

```bash
DWP_SESSION_COOKIE_SECURE=true
DWP_SESSION_COOKIE_SAME_SITE=Lax
JWT_SECRET=<managed-secret-at-least-256-bits>
```

상태 변경 요청은 Spring Security CSRF 보호와 `X-XSRF-TOKEN` Header를 사용합니다.

## Requirements

- Java 21 이상
- Docker Desktop
- Node.js 24 LTS와 Corepack
- Python 3.14 (`../dwp_agent/.venv`, 지원 범위 3.11~3.14)

## Documentation

유지 중인 개발 가이드와 아키텍처 결정의 목록 및 상태는
[Backend Documentation](docs/README.md)에서 확인합니다.

## Start

IntelliJ에서 각 Java 서비스를 실행·디버깅하거나 Run Configuration 오류를 해결하려면
[IntelliJ 백엔드 실행 및 테스트 가이드](docs/intellij-backend-run-and-test.md)를 먼저
확인합니다.

```bash
./dev up full
```

`full` 프로필은 PostgreSQL 18, Redis 7.4, Auth, Platform, People, Provider, Approval,
Agent, Gateway, Frontend를 기동합니다.
업무 기능을 추가하기 전 공통 웹 셸만 확인하려면 `core`, 프론트만 실행하려면
`web` 프로필을 사용할 수 있습니다. 이미 외부 Auth·Frontend가 실행 중이라면
`agent gateway` 프로필로 내부 실행 경로만 재기동할 수 있습니다.
`contracts` 프로필은 별도 Frontend·Agent 저장소 없이 네 개의 데이터 소유 서비스를
기동하며, Flyway와 시스템 코드 계약 감사용으로 사용합니다.

```bash
./dev doctor
./dev up core
./dev up contracts
./dev up agent gateway
./dev status
./dev logs gateway --follow
./dev stop
./dev down
```

개발용 초기 계정은 `admin@dwp.local` / `admin1234!`이며 배포 환경에서는 반드시
별도 사용자와 비밀값을 구성해야 합니다.

## Database Reset

기존 로컬 업무 테이블이 남은 Docker 볼륨을 제거하고 모든 소유 서비스의 스키마와
SKAX 개발 씨드를 새로 만들려면 아래 명령을 명시적으로 실행합니다.

```bash
./dev reset --yes
./dev up full
```

`reset --yes`는 로컬 PostgreSQL과 Redis 볼륨의 모든 데이터를 삭제합니다. 일반적인
`stop`과 `down`은 볼륨을 삭제하지 않습니다.

## Verification

```bash
./gradlew clean test --no-daemon
python3 -m py_compile scripts/devctl.py
./scripts/audit-code-contracts.sh
```

Pull Request와 `main`, `dev`, `dwp-dev` Push에서는 GitHub Actions가 빈 PostgreSQL
볼륨에 모든 서비스 Migration을 적용한 뒤 동일한 코드 계약 감사를 실행합니다.
