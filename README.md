# DWP Backend Starter

새 DWP 프로젝트를 시작하기 위한 공통 백엔드 기반입니다. 인증, 테넌트,
RBAC, 요청 추적만 포함하며 기존 업무 도메인 API와 데이터는 포함하지 않습니다.

## Modules

| Module | Port | Responsibility |
| --- | ---: | --- |
| `dwp-core` | - | API 응답, 예외, 공통 헤더와 요청 추적 |
| `dwp-auth-server` | 8001 | 로컬/OIDC 로그인, Browser Session, 테넌트, 사용자, RBAC |
| `dwp-gateway` | 8080 | 브라우저의 단일 API 진입점과 CORS |

인증 서버의 Flyway 마이그레이션만 데이터베이스 스키마를 생성합니다. 시작
스키마는 인증/RBAC과 서버 측 Session 폐기에 필요한 12개 테이블로 제한되어
있습니다. `sys_auth_sessions`는 JWT 원문이 아니라 `jti`, 사용자, 만료·폐기와
발급 Context만 저장합니다.

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

- Java 17
- Docker Desktop
- Node.js 20 이상과 Corepack
- Python 3.11 이상 (`../dwp_agent/.venv` 권장)

## Start

```bash
./dev up full
```

`full` 프로필은 PostgreSQL, Auth, Agent, Gateway, Frontend를 기동합니다.
업무 기능을 추가하기 전 공통 웹 셸만 확인하려면 `core`, 프론트만 실행하려면
`web` 프로필을 사용할 수 있습니다.

```bash
./dev doctor
./dev up core
./dev status
./dev logs gateway --follow
./dev stop
./dev down
```

개발용 초기 계정은 `admin` / `admin1234!`이며 배포 환경에서는 반드시
별도 사용자와 비밀값을 구성해야 합니다.

## Database Reset

기존 로컬 업무 테이블이 남은 Docker 볼륨을 제거하고 인증 스키마만 새로
만들려면 아래 명령을 명시적으로 실행합니다.

```bash
./dev reset --yes
./dev up full
```

`reset --yes`는 로컬 PostgreSQL 볼륨의 모든 데이터를 삭제합니다. 일반적인
`stop`과 `down`은 볼륨을 삭제하지 않습니다.

## Verification

```bash
./gradlew clean test --no-daemon
python3 -m py_compile scripts/devctl.py
```
