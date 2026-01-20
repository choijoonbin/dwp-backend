# DWP Backend

> **Spring Boot 3.x 기반 멀티 모듈 MSA(Microservices Architecture)**  
> **DWP Frontend(Host/Remote) 및 Aura-Platform(Python/FastAPI)과 통합되는 백엔드 인프라**

---

## 🎯 프로젝트 개요

DWP Backend는 DWP 시스템의 **Gateway / Auth / Main / Domain Services**로 구성된 마이크로서비스 아키텍처 기반 시스템입니다.  
특히 **Aura AI 플랫폼**과의 통합을 위해 **SSE 스트리밍 중계**, **HITL 승인 처리**, **AgentTask(장기 실행 작업) 관리**를 제공합니다.

### 주요 역할
- **API Gateway**: 모든 외부 요청의 진입점 및 라우팅 + 헤더 전파 + CORS + SSE 중계
- **인증/인가**: JWT 기반 멀티테넌시 지원 (`tenant_id` 클레임)
- **AI 에이전트 관리**: AgentTask 상태 추적 및 HITL(Human-in-the-loop) 승인 처리
- **서비스 간 통신**: FeignClient 기반 서비스 오케스트레이션 + 표준 헤더 전파
- **실시간 스트리밍**: `text/event-stream` 기반 SSE(POST SSE 포함) 응답 중계

### 협업 대상
- **Frontend**: Aura AI UI(SSE 스트리밍, HITL 승인 API, 표준 헤더 계약)
- **Aura-Platform**: AI 에이전트 서비스(Python/FastAPI, 9000) 통합

---

## 🧩 표준 통합 계약 (Frontend ↔ Backend)

### 1) 표준 헤더 (Strict Header Contract)
모든 외부 요청은 Gateway(8080)를 통해 들어오며, 아래 헤더는 통합 계약으로 고정합니다.

#### 필수 헤더
- `Authorization: Bearer {JWT}`
- `X-Tenant-ID: {tenantId}`
- `X-User-ID: {userId}`

#### 권장/상황별 헤더
- `X-Agent-ID: {agentSessionOrClientId}` (Aura 세션/클라이언트 식별)
- `X-DWP-Source: FRONTEND | AURA | INTERNAL | BATCH`
- `X-DWP-Caller-Type: AGENT | USER | SYSTEM`

> Gateway 및 FeignClient는 위 헤더를 다운스트림 서비스로 **누락 없이 전파**해야 합니다.

---

### 2) SSE 재연결 계약 (Last-Event-ID)
Frontend는 다음 SSE 클라이언트를 사용합니다.
- 네트워크 단절 시 Exponential Backoff 기반 재연결(최대 5회)
- 재연결 시 `Last-Event-ID` 헤더를 전송하여 중단 지점부터 재개 시도

Backend는 가능하면:
- SSE 이벤트에 재개 가능한 `eventId`를 포함하고,
- `Last-Event-ID` 수신 시 해당 지점부터 재개를 지원합니다.  
(재개 불가능 시, 명확한 에러/재시작 정책을 문서화합니다.)

---

### 3) SSE 이벤트 타입 계약 (Aura AI UI v1.0)
SSE 스트리밍은 아래 이벤트 타입을 지원합니다.
- `thought`, `plan_step`, `tool_execution`, `hitl`, `content`, `timeline_step_update`, `plan_step_update`

스트림 종료 시 반드시 아래를 전송합니다.
- `data: [DONE]\n\n`

---

## 📁 프로젝트 구조

```bash
dwp-backend/
├── dwp-core/                    # 공통 라이브러리 모듈 (ApiResponse, 예외, 상수, Redis, 헤더 계약)
├── dwp-gateway/                 # API Gateway (포트: 8080) - 라우팅/헤더전파/CORS/SSE 중계
├── dwp-auth-server/             # 인증 서버 (포트: 8001) - JWT 검증/인가
├── dwp-main-service/            # 메인 서비스 (포트: 8081) - AgentTask/HITL/장기 작업 상태 관리
└── services/
    ├── mail-service/            # 메일 서비스 (포트: 8082)
    ├── chat-service/            # 채팅 서비스 (포트: 8083)
    └── approval-service/        # 승인 서비스 (포트: 8084)

```

## 기술 스택

- **언어/프레임워크**: Java 17, Spring Boot 3.4.3
- **빌드 도구**: Gradle 8.5
- **API Gateway**: Spring Cloud Gateway (SSE 지원)
- **서비스 간 통신**: Spring Cloud OpenFeign
- **인증/인가**: Spring Security, OAuth2, JWT (Python-Java 호환)
- **데이터베이스**: PostgreSQL 15, JPA/Hibernate
- **캐시/세션**: Redis 7
- **이벤트 버스**: Redis Pub/Sub (서비스 간 이벤트 전파)
- **비동기 처리**: @Async, CompletableFuture
- **아키텍처**: MSA (Microservices Architecture)
- **AI 연동**: Aura-Platform (포트: 9000)

## 모듈별 상세 설명

### dwp-core
모든 서비스 모듈이 공통으로 사용하는 라이브러리 모듈입니다.
- **응답 처리**:
  - `ApiResponse<T>`: 공통 API 응답 포맷 (AI 파싱 최적화)
    - `AgentMetadata`: 에이전트 전용 메타데이터 필드 (추적 ID, 실행 단계, 신뢰도 등)
  - `ErrorCode`: 표준화된 에러 코드 열거형
  - `AgentStep`: AI 에이전트의 사고 과정 단계 DTO (id, title, description, status, confidence)
- **예외 처리**:
  - `BaseException`: 공통 예외 클래스
  - `GlobalExceptionHandler`: 전역 예외 처리
- **헤더 전파**:
  - `FeignHeaderInterceptor`: FeignClient 요청 시 자동 헤더 전파
  - `RequestSource`: 요청 출처 식별 (AURA, FRONTEND, INTERNAL, BATCH)
  - `HeaderConstants`: 표준 헤더 키 상수 정의
    - `X-DWP-Source`: 요청 출처
    - `X-DWP-Caller-Type`: 호출자 타입 (AGENT 등)
    - `X-Tenant-ID`: 테넌트 식별자
    - `X-User-ID`: 사용자 식별자
- **이벤트 시스템**:
  - `EventPublisher`: 이벤트 발행 인터페이스
  - `RedisEventPublisher`: Redis Pub/Sub 구현체
  - `EventChannels`: 표준화된 이벤트 채널 정의
  - `DomainEvent`: 도메인 이벤트 기본 클래스

### dwp-gateway
Spring Cloud Gateway를 사용한 API Gateway입니다.
- 모든 외부 요청의 진입점
- 서비스별 라우팅 설정
- **SSE(Server-Sent Events) 지원**: AI 스트리밍 응답 중계
  - Response Timeout: 300초 (5분)
  - Connect Timeout: 10초
  - 커넥션 풀 최적화 (max-connections: 500)
  - 자동 스트리밍 처리 (text/event-stream)
  - POST 요청에 대한 SSE 응답 지원 (프론트엔드 요구사항)
- **헤더 전파**: Authorization, X-Tenant-ID, X-DWP-Source, X-DWP-Caller-Type, X-User-ID 등
- **필터 구성**:
  - `HeaderPropagationFilter`: 헤더 전파 보장 및 로깅
  - `SseResponseHeaderFilter`: SSE 응답 헤더 보장 (Content-Type, Cache-Control, Transfer-Encoding)
  - `RequestBodyLoggingFilter`: POST 요청 body 로깅 및 전달 보장 (Aura-Platform 연동)
- CORS 설정 (환경 변수 기반)

**라우팅 설정:**
- `/api/aura/**` → `aura-platform` (포트 9000, AI 에이전트)
  - `/api/aura/test/stream` → SSE 스트리밍 엔드포인트
  - `/api/aura/hitl/**` → HITL 승인/거절 API (dwp-main-service로 라우팅)
- `/api/main/**` → `dwp-main-service` (포트 8081)
- `/api/auth/**` → `dwp-auth-server` (포트 8001)
- `/api/monitoring/**` → `dwp-auth-server` (포트 8001, 모니터링 수집 API)
- `/api/mail/**` → `mail-service` (포트 8082)
- `/api/chat/**` → `chat-service` (포트 8083)
- `/api/approval/**` → `approval-service` (포트 8084)

### dwp-auth-server (IAM)
사용자 인증 및 인가를 담당하는 서비스입니다.
- **멀티테넌시 IAM**: 테넌트별 데이터 격리 및 권한 관리 (Flyway V1, V2)
- **RBAC (Role-Based Access Control)**: 사용자/부서별 Role 할당 및 Resource(메뉴/버튼) 기반 Permission 제어
- **JWT 토큰 발급 및 검증**: Python (jose)와 Java (Spring Security) 호환 (HS256)
- **공통 코드 관리**: sys_code_groups/sys_codes 테이블 기반 코드 표준화 (P1-1)
- **메뉴 트리 관리**: sys_menus 테이블 기반 권한 필터링 메뉴 트리 API (P0-4)
- **모니터링 시스템**: 
  - Gateway 자동 API 호출 이력 수집 (sys_api_call_histories)
  - 페이지뷰/이벤트 수집 API (sys_page_view_events, sys_event_logs)
  - Admin 모니터링 조회 API (Visitors/Events/Timeseries) (P1-2)
- **주요 API**:
  - `POST /api/auth/login`: LOCAL 로그인 (BCrypt 검증, 공개 API)
  - `GET /api/auth/policy`: 테넌트별 로그인 정책 조회 (프론트엔드 UI 자동 분기용, 공개 API)
  - `GET /api/auth/idp`: 활성화된 Identity Provider 목록 조회 (공개 API)
  - `GET /api/auth/idp/{providerKey}`: 특정 Provider Key의 Identity Provider 조회 (공개 API)
  - `GET /api/auth/me`: 내 정보 및 역할 조회 (JWT 인증 필요)
  - `GET /api/auth/permissions`: 내 권한 목록(리소스별) 조회 (JWT 인증 필요)
  - `GET /api/auth/menus/tree`: 권한 기반 메뉴 트리 조회 (JWT 인증 필요)
  - `GET /api/admin/codes/**`: 공통 코드 조회 API (Admin 권한 필요)
  - `GET /api/admin/monitoring/**`: 모니터링 조회 API (Admin 권한 필요)
  - `POST /api/monitoring/page-view`: 페이지뷰 수집 (공개 API, X-Tenant-ID 필수)
  - `POST /api/monitoring/event`: 이벤트 수집 (공개 API, X-Tenant-ID 필수)
- **개발 편의**: `DevSeedRunner`를 통한 관리자 계정(`admin/admin1234!`) 자동 동기화
- **상세 명세**: 
  - [docs/FRONTEND_API_SPEC.md](docs/FRONTEND_API_SPEC.md)
  - [docs/ADMIN_MONITORING_API_SPEC.md](docs/ADMIN_MONITORING_API_SPEC.md)
  - [docs/CODE_MANAGEMENT.md](docs/CODE_MANAGEMENT.md)

### dwp-main-service
플랫폼의 메인 비즈니스 서비스를 담당합니다.
- 사용자 정보 관리
- 공통 메타데이터 관리
- **AI 에이전트 작업 관리**:
  - `AgentTask` 엔티티: AI 장기 실행 작업 상태 추적
    - `planSteps`: AI 실행 계획 단계 (JSON 형식, `AgentStep` 배열)
    - 작업 상태: REQUESTED → IN_PROGRESS → COMPLETED/FAILED
    - 진척도 업데이트 (0~100%)
    - 비동기 작업 실행 지원
- **HITL (Human-In-The-Loop) 관리**:
  - `HitlManager`: 승인 대기 중인 요청 관리 (Redis 기반)
  - 승인/거절 API: `/api/aura/hitl/approve`, `/api/aura/hitl/reject`
  - Redis Pub/Sub을 통한 에이전트 세션 신호 전송
  - `HitlSecurityInterceptor`: HITL 작업 시 JWT 권한 재검증
- Redis 세션 관리 (HITL 세션, 승인 요청 저장)
- 다른 서비스와의 통신 (FeignClient)

### services/*
개별 비즈니스 서비스 모듈들입니다.
- 각 서비스는 독립적인 데이터베이스 스키마를 가집니다.
- 타 서비스 데이터는 FeignClient를 통해 조회합니다.

## 로컬 개발 환경 설정

### Docker Compose를 사용한 인프라 구축

프로젝트 루트에서 다음 명령어로 PostgreSQL과 Redis를 실행합니다:

```bash
# Docker Compose로 인프라 실행
docker-compose up -d

# 실행 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs -f

# 중지
docker-compose down

# 데이터까지 완전 삭제 (주의!)
docker-compose down -v
```

### 데이터베이스 연결 정보

#### PostgreSQL
- **호스트**: `localhost`
- **포트**: `5432`
- **사용자명**: `dwp_user`
- **비밀번호**: `dwp_password`
- **데이터베이스 목록**:
  - `dwp_auth` - Auth Server 전용
  - `dwp_main` - Main Service 전용
  - `dwp_mail` - Mail Service 전용
  - `dwp_chat` - Chat Service 전용
  - `dwp_approval` - Approval Service 전용

#### Redis
- **호스트**: `localhost`
- **포트**: `6379`
- **용도**: 
  - 채팅 서비스 및 세션 관리
  - 이벤트 버스 (Redis Pub/Sub)
  - 서비스 간 이벤트 전파 (Aura-Platform 벡터 DB 동기화용)

### 데이터베이스 연결 예시

```bash
# PostgreSQL CLI로 연결
psql -h localhost -p 5432 -U dwp_user -d dwp_main

# 또는 Docker 컨테이너 내부에서
docker exec -it dwp-postgres psql -U dwp_user -d dwp_main
```

## 빌드 및 실행

### 사전 요구사항
- JDK 17 이상
- Gradle 8.5 이상
- Docker & Docker Compose (인프라 실행용)

### 전체 프로젝트 빌드
```bash
./gradlew build
```

### 개별 모듈 실행

#### 1. API Gateway 실행
```bash
cd dwp-gateway
../gradlew bootRun
```
또는
```bash
./gradlew :dwp-gateway:bootRun
```

#### 2. 인증 서버 실행
```bash
./gradlew :dwp-auth-server:bootRun
```

#### 3. 메인 서비스 실행
```bash
./gradlew :dwp-main-service:bootRun
```

#### 4. 비즈니스 서비스 실행
```bash
# 메일 서비스
./gradlew :services:mail-service:bootRun

# 채팅 서비스
./gradlew :services:chat-service:bootRun

# 승인 서비스
./gradlew :services:approval-service:bootRun
```

### 모든 서비스 동시 실행 (권장)
각 서비스를 별도의 터미널에서 실행하거나, IDE에서 각 모듈의 메인 클래스를 실행합니다.

### 테스트 실행
```bash
# 전체 테스트
./gradlew test

# 특정 모듈 테스트
./gradlew :dwp-gateway:test
./gradlew :dwp-auth-server:test

# 특정 테스트 클래스 실행
./gradlew :dwp-gateway:test --tests "AuraPlatformIntegrationTest"
./gradlew :dwp-auth-server:test --tests "JwtCompatibilityTest"
```

### 환경 변수 설정
```bash
# JWT 시크릿 키 (Python-Java 공유)
export JWT_SECRET=your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256

# Aura-Platform URI
export AURA_PLATFORM_URI=http://localhost:9000

# CORS 허용 Origin
export CORS_ALLOWED_ORIGINS=http://localhost:3039,http://localhost:4200

# 데이터베이스 설정
export DB_HOST=localhost
export DB_PORT=5432
export DB_USERNAME=dwp_user
export DB_PASSWORD=dwp_password
```

## 🚀 빠른 시작 (협업 개발자용)

### 프론트엔드 개발자
1. **[프론트엔드 통합 가이드](./docs/FRONTEND_INTEGRATION_GUIDE.md)** ⭐ - 시작하기
2. **[프론트엔드 API 스펙](./docs/FRONTEND_API_SPEC.md)** - 상세 API 명세
3. **주요 엔드포인트**:
   - SSE 스트리밍: `POST /api/aura/test/stream`
   - HITL 승인: `POST /api/aura/hitl/approve/{requestId}`
   - HITL 거절: `POST /api/aura/hitl/reject/{requestId}`

### Aura-Platform 개발자
1. **[Aura-Platform 통합 가이드](./docs/AURA_PLATFORM_INTEGRATION_GUIDE.md)** ⭐ - 시작하기
2. **[Aura-Platform 업데이트 사항](./docs/AURA_PLATFORM_UPDATE.md)** - 최신 변경사항
3. **[Aura-Platform 빠른 참조](./docs/AURA_PLATFORM_QUICK_REFERENCE.md)** - 핵심 정보
4. **주요 통합 포인트**:
   - SSE 응답: `Content-Type: text/event-stream` 필수
   - HITL 신호: Redis Pub/Sub 채널 `hitl:channel:{sessionId}`
   - 포트: **9000** (로컬 개발)

## API 엔드포인트

### Gateway를 통한 접근 (포트 8080)

**Base URL**: `http://localhost:8080`

#### 🤖 Aura-Platform (AI 에이전트) - 프론트엔드/Aura-Platform 핵심 엔드포인트

**SSE 스트리밍** (프론트엔드 → Aura-Platform):
- `POST http://localhost:8080/api/aura/test/stream` - AI 응답 스트리밍
  - **요청 본문**: `{"prompt": "...", "context": {...}}`
  - **Headers**: `Authorization: Bearer {JWT}`, `X-Tenant-ID: {tenant_id}`, `Content-Type: application/json`
  - **Response**: `Content-Type: text/event-stream`
  - **이벤트 타입**: `thought`, `plan_step`, `tool_execution`, `hitl`, `content`, `timeline_step_update`, `plan_step_update`
  - **스트림 종료**: `data: [DONE]\n\n`
  - **상세 스펙**: [프론트엔드 API 스펙](./docs/FRONTEND_API_SPEC.md)

**HITL (Human-In-The-Loop) 승인** (프론트엔드 → Main Service):
- `GET http://localhost:8080/api/aura/hitl/requests/{requestId}` - 승인 요청 조회
- `POST http://localhost:8080/api/aura/hitl/approve/{requestId}` - 승인 처리
  - **Body**: `{"userId": "user123"}`
  - **Headers**: `Authorization`, `X-Tenant-ID`, `X-User-ID` 필수
  - **동작**: Redis Pub/Sub으로 Aura-Platform에 승인 신호 전송
- `POST http://localhost:8080/api/aura/hitl/reject/{requestId}` - 거절 처리
  - **Body**: `{"userId": "user123", "reason": "사용자 거절"}`
  - **Headers**: `Authorization`, `X-Tenant-ID`, `X-User-ID` 필수
- `GET http://localhost:8080/api/aura/hitl/signals/{sessionId}` - 신호 조회 (Aura-Platform용)

#### 메인 서비스
- `GET http://localhost:8080/api/main/health` - 메인 서비스 헬스 체크
- `GET http://localhost:8080/api/main/info` - 메인 서비스 정보

#### AI 에이전트 작업 관리
- `POST http://localhost:8080/api/main/agent/tasks` - 새 작업 생성
- `GET http://localhost:8080/api/main/agent/tasks/{taskId}` - 작업 조회
- `GET http://localhost:8080/api/main/agent/tasks?userId={userId}&tenantId={tenantId}` - 사용자별 작업 목록
- `POST http://localhost:8080/api/main/agent/tasks/{taskId}/start` - 작업 시작
- `PATCH http://localhost:8080/api/main/agent/tasks/{taskId}/progress` - 진척도 업데이트
- `POST http://localhost:8080/api/main/agent/tasks/{taskId}/complete` - 작업 완료
- `POST http://localhost:8080/api/main/agent/tasks/{taskId}/fail` - 작업 실패 처리
- `POST http://localhost:8080/api/main/agent/tasks/{taskId}/execute` - 비동기 작업 실행

#### 인증 서버
- `GET http://localhost:8080/api/auth/health` - 인증 서버 헬스 체크
- `GET http://localhost:8080/api/auth/policy` - 테넌트별 로그인 정책 조회 (프론트엔드 UI 자동 분기용)
  - **Headers**: `X-Tenant-ID: {tenantId}` (필수)
  - **응답**: `{"success": true, "data": {"tenantId": 1, "defaultLoginType": "LOCAL", "allowedLoginTypes": ["LOCAL"], "localLoginEnabled": true, "ssoLoginEnabled": false, "requireMfa": false}}`
  - **상세 명세**: [docs/AUTH_POLICY_SPEC.md](docs/AUTH_POLICY_SPEC.md)
- `GET http://localhost:8080/api/auth/idp` - 활성화된 Identity Provider 목록 조회
  - **Headers**: `X-Tenant-ID: {tenantId}` (필수)
  - **응답**: `{"success": true, "data": [{"tenantId": 1, "enabled": true, "providerType": "OIDC", "providerKey": "AZURE_AD", ...}]}`
- `GET http://localhost:8080/api/auth/idp/{providerKey}` - 특정 Provider Key의 Identity Provider 조회
- `POST http://localhost:8080/api/auth/login` - 로그인 및 JWT 토큰 발급
  - **요청 본문**: `{"username": "...", "password": "...", "tenantId": "..."}`
  - **Headers**: `Content-Type: application/json` (필수)
  - **응답**: `{"status": "SUCCESS", "data": {"accessToken": "...", "tokenType": "Bearer", "expiresIn": 3600, "userId": "...", "tenantId": "..."}}`
  - **상세 가이드**: [로그인 API 문제 해결 가이드](./docs/LOGIN_API_TROUBLESHOOTING.md)
- `GET http://localhost:8080/api/auth/me` - 내 정보 조회 (JWT 인증 필요)
  - **Headers**: `Authorization: Bearer {JWT}`, `X-Tenant-ID: {tenantId}` (필수)
  - **응답**: `{"status": "SUCCESS", "data": {"userId": 1, "displayName": "...", "email": "...", "tenantId": 1, "tenantCode": "dev", "roles": ["ADMIN"]}}`
- `GET http://localhost:8080/api/auth/permissions` - 내 권한 목록 조회 (JWT 인증 필요)
  - **Headers**: `Authorization: Bearer {JWT}`, `X-Tenant-ID: {tenantId}` (필수)
  - **응답**: `{"status": "SUCCESS", "data": [{"resourceType": "MENU", "resourceKey": "menu.dashboard", "resourceName": "Dashboard", "permissionCode": "VIEW", "permissionName": "조회", "effect": "ALLOW"}, ...]}`
- `GET http://localhost:8080/api/auth/menus/tree` - 권한 기반 메뉴 트리 조회 (JWT 인증 필요)
  - **Headers**: `Authorization: Bearer {JWT}`, `X-Tenant-ID: {tenantId}` (선택)
- `GET http://localhost:8080/api/auth/info` - 인증 서버 정보 (JWT 인증 필요)

#### 모니터링 API (Admin)
- `GET http://localhost:8080/api/admin/monitoring/summary` - 모니터링 요약 정보 조회
- `GET http://localhost:8080/api/admin/monitoring/page-views` - 페이지뷰 목록 조회 (필터링 지원)
- `GET http://localhost:8080/api/admin/monitoring/api-histories` - API 호출 이력 조회 (필터링 지원)
- `GET http://localhost:8080/api/admin/monitoring/visitors` - 방문자 목록 조회 (P1-2)
- `GET http://localhost:8080/api/admin/monitoring/events` - 이벤트 로그 목록 조회 (P1-2)
- `GET http://localhost:8080/api/admin/monitoring/timeseries` - 시계열 데이터 조회 (P1-2)
- **수집 API** (인증 불필요, X-Tenant-ID 필수):
  - `POST http://localhost:8080/api/monitoring/page-view` - 페이지뷰 수집
  - `POST http://localhost:8080/api/monitoring/event` - 이벤트 수집
- **상세 명세**: 
  - [docs/ADMIN_MONITORING_API_SPEC.md](docs/ADMIN_MONITORING_API_SPEC.md) - 전체 모니터링 API 스펙
  - [docs/ADMIN_MONITORING_VISITORS_EVENTS_API_SPEC.md](docs/ADMIN_MONITORING_VISITORS_EVENTS_API_SPEC.md) - 방문자/이벤트 API 상세 스펙
  - [docs/ADMIN_MONITORING_TIMESERIES_API_SPEC.md](docs/ADMIN_MONITORING_TIMESERIES_API_SPEC.md) - 시계열 API 스펙
  - [docs/ADMIN_MONITORING_TIMESERIES_API_RESPONSE_EXAMPLES.md](docs/ADMIN_MONITORING_TIMESERIES_API_RESPONSE_EXAMPLES.md) - 시계열 API 응답 예시
  - [docs/EVENT_LOGS_TABLE_AND_API.md](docs/EVENT_LOGS_TABLE_AND_API.md) - 이벤트 로그 테이블 및 수집 API 가이드
  - [docs/MONITORING_API_COMPARISON.md](docs/MONITORING_API_COMPARISON.md) - 프론트엔드-백엔드 API 비교 문서

#### 기타 서비스
- `GET http://localhost:8080/api/mail/health` - 메일 서비스 헬스 체크
- `GET http://localhost:8080/api/chat/health` - 채팅 서비스 헬스 체크
- `GET http://localhost:8080/api/approval/health` - 승인 서비스 헬스 체크

### 직접 접근 (포트 정보)

| 서비스 | 포트 | URL | 용도 |
|--------|------|-----|------|
| **Gateway** | 8080 | `http://localhost:8080` | 모든 외부 요청 진입점 |
| **Auth Server** | 8001 | `http://localhost:8001` | 인증/인가 서비스 |
| **Main Service** | 8081 | `http://localhost:8081` | 메인 비즈니스 로직, HITL 관리 |
| **Aura-Platform** | 9000 | `http://localhost:9000` | AI 에이전트 서비스 (Python/FastAPI) |
| Mail Service | 8082 | `http://localhost:8082` | 메일 서비스 |
| Chat Service | 8083 | `http://localhost:8083` | 채팅 서비스 |
| Approval Service | 8084 | `http://localhost:8084` | 승인 서비스 |

**⚠️ 중요**: 
- Auth Server는 포트 **8001**을 사용합니다 (Aura-Platform과의 포트 충돌 방지)
- Aura-Platform은 포트 **9000**을 사용합니다
- 모든 외부 요청은 Gateway(포트 8080)를 통해 접근해야 합니다

## 개발 가이드

### 코드 작성 규칙
1. **패키지 구조**: `com.dwp.services.[module_name]`
2. **응답 규격**: 모든 API 응답은 `ApiResponse<T>`로 감싸서 반환
   - 에이전트 전용 메타데이터는 `AgentMetadata` 필드에 포함
3. **예외 처리**: `@RestControllerAdvice`를 사용한 전역 예외 처리
4. **계층 분리**: Controller → Service → Repository 구조 준수, DTO와 Entity 분리
5. **헤더 전파**: 
   - FeignClient 사용 시 `X-DWP-Source`, `X-Tenant-ID`, `X-DWP-Caller-Type` 자동 전파
   - Gateway를 통한 요청 시 모든 헤더가 다운스트림 서비스로 전파
6. **이벤트 발행**: 중요 데이터 변경 시 `EventPublisher`를 통해 이벤트 발행
7. **비동기 처리**: 장기 실행 작업은 `@Async` 또는 `CompletableFuture` 사용
8. **HITL 보안**: 승인/거절 작업 시 반드시 JWT 권한 재검증 (`HitlSecurityInterceptor`)
9. **에이전트 데이터**: `AgentStep` DTO를 사용하여 AI 실행 계획 단계 표현

### 새 서비스 추가하기
1. `services/` 디렉토리에 새 모듈 생성
2. `settings.gradle`에 모듈 추가
3. `build.gradle` 설정 (dwp-core 의존성 포함)
4. `application.yml` 설정 (고유 포트 지정)
5. Gateway의 `application.yml`에 라우팅 규칙 추가

## 데이터베이스

로컬 개발 환경에서는 Docker Compose를 통해 PostgreSQL을 사용합니다.
- 각 서비스는 독립적인 데이터베이스를 가집니다 (같은 PostgreSQL 인스턴스 내의 다른 스키마).
- 데이터베이스 초기화는 `docker/postgres/init.sql` 스크립트로 자동 수행됩니다.

### 데이터베이스 스키마 관리

각 서비스는 JPA의 `ddl-auto: update` 설정을 사용하여 자동으로 테이블을 생성/수정합니다.
프로덕션 환경에서는 Flyway나 Liquibase 같은 마이그레이션 도구 사용을 권장합니다.

## 📚 문서

프로젝트 관련 상세 문서는 [`docs/`](./docs/) 폴더를 참고하세요.

### 👥 협업 팀별 핵심 문서

#### 🔗 통합 체크리스트 (모든 팀 필수)
- **[통/협업 체크리스트](./docs/COLLABORATION_CHECKLIST.md)** ⭐⭐⭐ - 포트 충돌, 사용자 ID 일관성, SSE POST 방식 등 통합 시 확인 사항

#### 🎨 프론트엔드 개발팀
- **[프론트엔드 통합 가이드](./docs/FRONTEND_INTEGRATION_GUIDE.md)** ⭐ - 시작하기
- **[프론트엔드 확인 요청 사항](./docs/FRONTEND_VERIFICATION_REQUIREMENTS.md)** ⭐⭐⭐ - 통합 전 필수 확인 사항
- **[프론트엔드 확인 답변 검토](./docs/FRONTEND_VERIFICATION_RESPONSE.md)** ⭐⭐⭐ - 프론트엔드 구현 확인 및 통합 테스트 가이드
- **[프론트엔드 확인 체크리스트](./docs/FRONTEND_VERIFICATION_CHECKLIST.md)** - 백엔드 점검 결과
- **[프론트엔드 API 스펙](./docs/FRONTEND_API_SPEC.md)** - 상세 API 명세서
- [Aura AI UI 통합 가이드](./docs/AURA_UI_INTEGRATION.md) - UI 통합 상세 가이드

#### 🔧 백엔드 개발팀 (통합 테스트)
- **[백엔드 통합 테스트 체크리스트](./docs/BACKEND_INTEGRATION_TEST_CHECKLIST.md)** ⭐⭐⭐ - 백엔드 통합 테스트 필수 확인 사항
- **[HITL API 테스트 가이드](./docs/HITL_API_TEST_GUIDE.md)** ⭐⭐⭐ - HITL API 500 에러 해결 및 테스트 절차
- **[백엔드 통합 테스트 결과](./docs/BACKEND_INTEGRATION_TEST_RESULTS.md)** - 실제 테스트 결과 기록

#### 🤖 Aura-Platform 개발팀
- **[Aura-Platform 통합 가이드](./docs/AURA_PLATFORM_INTEGRATION_GUIDE.md)** ⭐ - 시작하기
- **[Aura-Platform 확인 요청 사항](./docs/AURA_PLATFORM_VERIFICATION_REQUIREMENTS.md)** ⭐⭐⭐ - 통합 전 필수 확인 사항
- **[Aura-Platform 통합 체크리스트 응답](./docs/AURA_PLATFORM_INTEGRATION_RESPONSE.md)** ⭐⭐⭐ - Aura-Platform 체크리스트에 대한 백엔드 응답
- **[Aura-Platform 업데이트 사항](./docs/AURA_PLATFORM_UPDATE.md)** - 최신 변경사항 및 요구사항
- **[Aura-Platform 빠른 참조](./docs/AURA_PLATFORM_QUICK_REFERENCE.md)** - 핵심 정보 빠른 참조
- [Aura-Platform 전달 문서](./docs/AURA_PLATFORM_HANDOFF.md) - 전달 가이드
- [Aura-Platform Backend 전달 문서](./docs/AURA_PLATFORM_BACKEND_HANDOFF.md) - Aura-Platform에서 전달받은 문서

### 🔐 인증 및 보안
- [인증 정책 및 Identity Provider 스펙](./docs/AUTH_POLICY_SPEC.md) ⭐ - 테넌트별 로그인 정책 및 SSO Provider 관리
- [JWT 호환성 가이드](./docs/JWT_COMPATIBILITY_GUIDE.md) - Python-Java JWT 통합
- [JWT 이슈 요약](./docs/JWT_ISSUE_SUMMARY.md) - JWT 관련 이슈 및 해결
- [로그인 API 문제 해결 가이드](./docs/LOGIN_API_TROUBLESHOOTING.md) - 로그인 API 디버깅 및 문제 해결

### 🧪 테스트 및 검증
- [통합 테스트 가이드](./docs/INTEGRATION_TEST_GUIDE.md) - Gateway 통합 테스트
- [Aura-Platform 통합 테스트](./docs/AURA_INTEGRATION_TEST.md) - Aura-Platform 테스트 가이드
- [Aura-Platform 통합 검증](./docs/AURA_INTEGRATION_VERIFICATION.md) - 검증 결과
- [준비 완료 확인서](./docs/SETUP_VERIFICATION.md) - Aura-Platform 통합 준비 상태
- [AI 인프라 구축 완료 보고](./docs/AI_AGENT_SETUP_COMPLETE.md)

### 🛠️ 개발 도구 및 설정
- [IDE 새로고침 가이드](./docs/IDE_REFRESH_GUIDE.md) - IDE 오류 해결 및 Gradle 새로고침
- [CORS 설정 가이드](./docs/CORS_CONFIGURATION.md) - CORS 설정 방법

### 📊 모니터링 및 관리
- [Admin 모니터링 API 스펙](./docs/ADMIN_MONITORING_API_SPEC.md) - 전체 모니터링 API 스펙
- [방문자/이벤트 API 스펙](./docs/ADMIN_MONITORING_VISITORS_EVENTS_API_SPEC.md) - 방문자 및 이벤트 조회 API 상세 스펙
- [시계열 API 스펙](./docs/ADMIN_MONITORING_TIMESERIES_API_SPEC.md) - 시계열 데이터 조회 API 스펙
- [시계열 API 응답 예시](./docs/ADMIN_MONITORING_TIMESERIES_API_RESPONSE_EXAMPLES.md) - 시계열 API 실제 응답 예시 및 프론트엔드 활용 가이드
- [이벤트 로그 테이블 및 API](./docs/EVENT_LOGS_TABLE_AND_API.md) - 이벤트 로그 테이블 구조 및 수집 API 가이드
- [모니터링 API 비교](./docs/MONITORING_API_COMPARISON.md) - 프론트엔드-백엔드 API 비교 및 정합성 검증 문서
- [리소스 데이터 소스 가이드](./docs/COM_RESOURCES_DATA_SOURCE.md) - com_resources 테이블 데이터 소스 및 관리 방법 가이드

### 📚 전체 문서 목록

**프론트엔드 통합:**
- `FRONTEND_INTEGRATION_GUIDE.md` ⭐ - 프론트엔드 통합 가이드
- `FRONTEND_API_SPEC.md` ⭐ - 프론트엔드 API 스펙 (최신)

**Aura-Platform 통합:**
- `AURA_PLATFORM_INTEGRATION_GUIDE.md` ⭐ - 상세 통합 가이드
- `AURA_PLATFORM_QUICK_REFERENCE.md` - 빠른 참조
- `AURA_PLATFORM_HANDOFF.md` - 전달 문서
- `AURA_PLATFORM_UPDATE.md` ⭐ - 최신 업데이트 사항
- `AURA_PLATFORM_BACKEND_HANDOFF.md` - Aura-Platform에서 전달받은 문서
- `AURA_UI_INTEGRATION.md` - Aura AI UI 통합 가이드
- `AURA_INTEGRATION_TEST.md` - 통합 테스트
- `AURA_INTEGRATION_VERIFICATION.md` - 검증 결과
- `AURA_INTEGRATION_SUMMARY.md` - 통합 요약

**AI 에이전트 인프라:**
- `AI_AGENT_INFRASTRUCTURE.md` - 인프라 가이드
- `AI_AGENT_SETUP_COMPLETE.md` - 구축 완료 보고
- `NEXT_STEPS.md` - 다음 단계 로드맵

**인증 및 보안:**
- `JWT_COMPATIBILITY_GUIDE.md` - JWT 호환성
- `JWT_ISSUE_SUMMARY.md` - JWT 이슈 요약

**테스트 및 검증:**
- `INTEGRATION_TEST_GUIDE.md` - 통합 테스트
- `SETUP_VERIFICATION.md` - 준비 완료 확인서

**개발 도구:**
- `IDE_REFRESH_GUIDE.md` - IDE 새로고침
- `CORS_CONFIGURATION.md` - CORS 설정

**기타:**
- `README_UPDATE_LOG.md` - README 업데이트 로그

## Aura AI UI 통합 완료 내역

프론트엔드 Aura AI UI 명세 v1.0에 맞춰 백엔드 연동 로직이 완료되었습니다.

### ✅ dwp-core 확장

- **`AgentStep` DTO**: AI 에이전트의 사고 과정 단계 표현
  - 필드: `id`, `title`, `description`, `status`, `confidence`, `result`, `startedAt`, `completedAt`
- **`AgentMetadata`**: `ApiResponse`에 에이전트 전용 메타데이터 필드 추가
  - 필드: `traceId`, `steps`, `confidence`, `additionalData`
- **`HeaderConstants`**: 표준 헤더 키 상수 정의
  - `X-DWP-Caller-Type: AGENT` 상수 추가
  - `X-DWP-Source`, `X-Tenant-ID`, `X-User-ID`, `Authorization` 등

### ✅ dwp-gateway 최적화

- **SSE 스트리밍 최적화**:
  - Response Timeout: 300초 (5분) 보장
  - 커넥션 풀 최적화 (max-connections: 500)
  - 자동 스트리밍 처리 (`text/event-stream`)
- **헤더 전파 강화**:
  - `HeaderPropagationFilter`에 `X-DWP-Caller-Type` 헤더 추가
  - 모든 헤더 전파 보장 및 로깅
- **라우팅 설정**:
  - `/api/aura/**` → Aura-Platform (포트 9000)
  - `/api/aura/test/stream` → SSE 스트리밍 엔드포인트
  - `/api/aura/hitl/**` → HITL 승인/거절 API

### ✅ dwp-main-service 구현

- **`AgentTask` 엔티티 확장**:
  - `planSteps` 필드 추가 (JSON 형식, `AgentStep` 배열 저장)
- **HITL Manager**:
  - `HitlManager`: Redis 기반 승인 요청 관리
  - 승인/거절 API: `/api/aura/hitl/approve`, `/api/aura/hitl/reject`
  - Redis Pub/Sub을 통한 에이전트 세션 신호 전송
  - 세션 관리 (TTL: 30분~60분)
- **보안 인터셉터**:
  - `HitlSecurityInterceptor`: HITL 작업 시 JWT 권한 재검증
  - 필수 헤더 검증 (Authorization, X-Tenant-ID, X-User-ID)

### 📚 관련 문서

- [프론트엔드 API 스펙](./docs/FRONTEND_API_SPEC.md) - 프론트엔드에서 전달받은 상세 API 스펙 (최신)
- [프론트엔드 통합 가이드](./docs/FRONTEND_INTEGRATION_GUIDE.md) - 프론트엔드 개발자를 위한 통합 가이드
- [Aura AI UI 통합 가이드](./docs/AURA_UI_INTEGRATION.md) - 상세 통합 가이드
- [Aura-Platform 통합 테스트](./docs/AURA_INTEGRATION_TEST.md) - 테스트 가이드
- [Aura-Platform 통합 검증](./docs/AURA_INTEGRATION_VERIFICATION.md) - 검증 결과
- [Aura-Platform Backend 전달 문서](./docs/AURA_PLATFORM_BACKEND_HANDOFF.md) - Aura-Platform에서 전달받은 통합 문서
- [Aura-Platform 업데이트 사항](./docs/AURA_PLATFORM_UPDATE.md) - Aura-Platform에 전달할 업데이트 사항

## 향후 계획

### 완료된 작업
- [x] **AI 에이전트 인프라 구축 완료** (2024-01)
  - [x] Gateway에 Aura-Platform 라우팅 추가 (`/api/aura/**` → 포트 9000)
  - [x] Auth Server 포트 변경 (8000 → 8001) - Aura-Platform과 포트 충돌 해결
  - [x] SSE 지원 (AI 스트리밍 응답, 300초 타임아웃)
  - [x] FeignClient 헤더 자동 전파 (`X-DWP-Source`, `X-Tenant-ID`)
  - [x] AI 파싱 최적화 (ApiResponse에 `success` 필드 추가)
  - [x] AgentTask 관리 (AI 장기 실행 작업 상태 추적)
  - [x] Redis Pub/Sub 기반 이벤트 시스템 구축
  - [x] Gateway 통합 테스트 코드 작성
- [x] **JWT 인증 시스템 구축** (2024-01)
  - [x] JWT 검증 설정 (HS256, Python-Java 호환)
  - [x] Security Filter Chain 구성
  - [x] JWT 호환성 테스트 작성
  - [x] Python-Java JWT 통합 가이드 작성
- [x] **로그인 API 구현 완료** (2026-01)
  - [x] 로그인 엔드포인트 구현 (`POST /api/auth/login`)
  - [x] JWT 토큰 발급 기능 구현
  - [x] Security 설정 최적화 (permitAll 경로 명시적 처리)
  - [x] Gateway 요청 body 로깅 필터 확장 (Auth Server 지원)
  - [x] 로그인 API 문제 해결 가이드 작성
- [x] **Aura AI UI 백엔드 연동 완료** (2024-01)
  - [x] `AgentStep` DTO 추가 (AI 사고 과정 단계 표현)
  - [x] `AgentMetadata` 추가 (ApiResponse 확장)
  - [x] `HeaderConstants` 추가 (`X-DWP-Caller-Type: AGENT` 상수)
  - [x] Gateway SSE 스트리밍 최적화 (커넥션 풀, 타임아웃)
  - [x] `AgentTask` 엔티티에 `planSteps` 필드 추가
  - [x] HITL Manager 구현 (승인/거절 API, Redis 세션 관리)
  - [x] HITL 보안 인터셉터 (JWT 권한 재검증)
  - [x] `/api/aura/hitl/**` 엔드포인트 구현

### 예정된 작업
- [ ] Aura-Platform (AI Agent) 서비스 구현 및 연동 (포트 9000)
- [ ] 벡터 DB 연동 (이벤트 기반 자동 동기화)
- [ ] 실제 사용자 인증 로직 구현 (DB 조회 및 비밀번호 검증)
- [ ] RBAC 권한 관리 (AI 에이전트 전용 Scope 포함)
- [ ] Service Discovery (Eureka) 도입
- [ ] Config Server 도입
- [ ] Circuit Breaker (Resilience4j) 적용
- [ ] 로깅 및 모니터링 (ELK Stack, Prometheus)
- [ ] Docker 컨테이너화
- [ ] Kubernetes 배포 설정

## 라이선스

Copyright (c) 2024 DWP

