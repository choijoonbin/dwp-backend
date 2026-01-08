# DWP Backend

Spring Boot 3.x 기반의 멀티 모듈 MSA(Microservices Architecture) 프로젝트입니다.

## 프로젝트 구조

```
dwp-backend/
├── dwp-core/                    # 공통 라이브러리 모듈
│   ├── common/                  # 공통 응답 DTO (ApiResponse)
│   └── exception/               # 공통 예외 처리
├── dwp-gateway/                 # API Gateway (포트: 8080)
├── dwp-auth-server/             # 인증 서버 (포트: 8000)
├── dwp-main-service/            # 메인 비즈니스 서비스 (포트: 8081)
└── services/
    ├── mail-service/            # 메일 서비스 (포트: 8082)
    ├── chat-service/            # 채팅 서비스 (포트: 8083)
    └── approval-service/        # 승인 서비스 (포트: 8084)
```

## 기술 스택

- **언어/프레임워크**: Java 17, Spring Boot 3.4.3
- **빌드 도구**: Gradle 8.5
- **API Gateway**: Spring Cloud Gateway
- **서비스 간 통신**: Spring Cloud OpenFeign
- **인증/인가**: Spring Security, OAuth2, JWT (예정)
- **데이터베이스**: PostgreSQL 15, JPA/Hibernate
- **캐시/세션**: Redis 7
- **아키텍처**: MSA (Microservices Architecture)

## 모듈별 상세 설명

### dwp-core
모든 서비스 모듈이 공통으로 사용하는 라이브러리 모듈입니다.
- `ApiResponse<T>`: 공통 API 응답 포맷
- `BaseException`: 공통 예외 클래스
- `GlobalExceptionHandler`: 전역 예외 처리

### dwp-gateway
Spring Cloud Gateway를 사용한 API Gateway입니다.
- 모든 외부 요청의 진입점
- 서비스별 라우팅 설정
- 공통 인증 필터 (예정)

**라우팅 설정:**
- `/api/main/**` → `dwp-main-service` (포트 8081)
- `/api/auth/**` → `dwp-auth-server` (포트 8000)
- `/api/mail/**` → `mail-service` (포트 8082)
- `/api/chat/**` → `chat-service` (포트 8083)
- `/api/approval/**` → `approval-service` (포트 8084)

### dwp-auth-server
사용자 인증 및 인가를 담당하는 서비스입니다.
- OAuth2, JWT 기반 인증 (구현 예정)
- 사용자 토큰 발급 및 검증

### dwp-main-service
플랫폼의 메인 비즈니스 서비스를 담당합니다.
- 사용자 정보 관리
- 공통 메타데이터 관리
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
- **용도**: 채팅 서비스 및 세션 관리

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

## API 엔드포인트

### Gateway를 통한 접근 (포트 8080)
- `GET http://localhost:8080/api/main/health` - 메인 서비스 헬스 체크
- `GET http://localhost:8080/api/main/info` - 메인 서비스 정보
- `GET http://localhost:8080/api/auth/health` - 인증 서버 헬스 체크
- `GET http://localhost:8080/api/mail/health` - 메일 서비스 헬스 체크
- `GET http://localhost:8080/api/chat/health` - 채팅 서비스 헬스 체크
- `GET http://localhost:8080/api/approval/health` - 승인 서비스 헬스 체크

### 직접 접근
- Gateway: `http://localhost:8080`
- Auth Server: `http://localhost:8000`
- Main Service: `http://localhost:8081`
- Mail Service: `http://localhost:8082`
- Chat Service: `http://localhost:8083`
- Approval Service: `http://localhost:8084`

## 개발 가이드

### 코드 작성 규칙
1. **패키지 구조**: `com.dwp.services.[module_name]`
2. **응답 규격**: 모든 API 응답은 `ApiResponse<T>`로 감싸서 반환
3. **예외 처리**: `@RestControllerAdvice`를 사용한 전역 예외 처리
4. **계층 분리**: Controller → Service → Repository 구조 준수, DTO와 Entity 분리

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

## 문서

프로젝트 관련 상세 문서는 [`docs/`](./docs/) 폴더를 참고하세요.

- [서비스 실행 가이드](./docs/SERVICE_START_GUIDE.md)
- [데이터베이스 연결 정보](./docs/DATABASE_INFO.md)
- [IDE 설정 가이드](./docs/IDE_SETUP.md)
- [Gateway 라우팅 테스트](./docs/GATEWAY_ROUTING_TEST.md)
- [전체 문서 목록](./docs/README.md)

## 향후 계획

- [ ] Spring Security + OAuth2 + JWT 인증 구현
- [ ] Service Discovery (Eureka) 도입
- [ ] Config Server 도입
- [ ] Circuit Breaker (Resilience4j) 적용
- [ ] 로깅 및 모니터링 (ELK Stack, Prometheus)
- [ ] Docker 컨테이너화
- [ ] Kubernetes 배포 설정

## 라이선스

Copyright (c) 2024 DWP

