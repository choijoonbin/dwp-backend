# DWP Backend Runbook

## 목적 (C33)
실운영 환경에서 서비스 기동, 모니터링, 장애 대응을 위한 운영 가이드

---

## 서비스 기동 순서

### 1. 필수 인프라 기동
```bash
# PostgreSQL
docker-compose up -d postgres

# Redis
docker-compose up -d redis
```

### 2. 서비스 기동 순서
```bash
# 1) Gateway (먼저 기동)
./gradlew :dwp-gateway:bootRun

# 2) Auth Server
./gradlew :dwp-auth-server:bootRun

# 3) Main Service
./gradlew :dwp-main-service:bootRun

# 4) Domain Services (병렬 가능)
./gradlew :services:mail-service:bootRun
./gradlew :services:chat-service:bootRun
./gradlew :services:approval-service:bootRun
```

**⚠️ 주의**: Gateway는 반드시 먼저 기동 (Downstream Health Check 필요)

---

## 필수 환경 변수

### Gateway (dwp-gateway)
```bash
# 필수
SERVICE_AUTH_URL=http://localhost:8001
SERVICE_MAIN_URL=http://localhost:8081
SERVICE_MAIL_URL=http://localhost:8082
SERVICE_CHAT_URL=http://localhost:8083
SERVICE_APPROVAL_URL=http://localhost:8084
AURA_PLATFORM_URI=http://localhost:9000

# 선택 (기본값 존재)
SERVER_PORT=8080
```

### Auth Server (dwp-auth-server)
```bash
# 필수
DB_HOST=localhost
DB_PORT=5432
DB_NAME=dwp_auth
DB_USERNAME=dwp_user
DB_PASSWORD=dwp_password
JWT_SECRET=your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256

# 선택
SERVER_PORT=8001
JPA_DDL_AUTO=validate
RBAC_CACHE_TTL_SECONDS=300
```

### Main Service (dwp-main-service)
```bash
# 필수
DB_HOST=localhost
DB_PORT=5432
DB_NAME=dwp_main
DB_USERNAME=dwp_user
DB_PASSWORD=dwp_password

# 선택
SERVER_PORT=8081
JPA_DDL_AUTO=validate
```

### Domain Services (mail/chat/approval)
```bash
# 각 서비스별 DB 설정
DB_HOST=localhost
DB_PORT=5432
DB_NAME=dwp_{service}  # dwp_mail, dwp_chat, dwp_approval
DB_USERNAME=dwp_user
DB_PASSWORD=dwp_password
```

---

## Health Check 엔드포인트

### 전체 서비스 공통
| 엔드포인트 | 목적 | 응답 예시 |
|-----------|------|----------|
| `/actuator/health` | 서비스 전체 상태 | `{"status":"UP"}` |
| `/actuator/health/readiness` | K8s Readiness Probe | `{"status":"UP"}` |
| `/actuator/health/liveness` | K8s Liveness Probe | `{"status":"UP"}` |
| `/actuator/metrics` | Micrometer 메트릭 목록 | JSON |
| `/actuator/prometheus` | Prometheus 스크랩 엔드포인트 | Prometheus 포맷 |

### 서비스별 포트
- Gateway: `http://localhost:8080/actuator/health`
- Auth: `http://localhost:8001/actuator/health`
- Main: `http://localhost:8081/actuator/health`
- Mail: `http://localhost:8082/actuator/health`
- Chat: `http://localhost:8083/actuator/health`
- Approval: `http://localhost:8084/actuator/health`

---

## 장애 시 1차 확인 목록

### 1단계: Health Check
```bash
# 모든 서비스 Health 확인
curl http://localhost:8080/actuator/health  # Gateway
curl http://localhost:8001/actuator/health  # Auth
curl http://localhost:8081/actuator/health  # Main
```

### 2단계: 로그 확인
```bash
# Correlation ID로 추적
grep "correlationId=<ID>" logs/*.log

# 에러 로그
grep "ERROR" logs/*.log | tail -20
```

### 3단계: DB 연결 확인
```bash
# PostgreSQL 연결 테스트
psql -h localhost -U dwp_user -d dwp_auth -c "SELECT 1;"
```

### 4단계: 환경 변수 확인
```bash
# Gateway 라우팅 변수 확인
echo $SERVICE_AUTH_URL
echo $SERVICE_MAIN_URL

# DB 변수 확인
echo $DB_HOST
echo $DB_NAME
```

---

## 자주 발생하는 문제

### 문제 1: Gateway에서 503 Service Unavailable
**원인**: Downstream 서비스 미기동 또는 Health Check 실패

**해결**:
```bash
# Downstream 서비스 재기동
./gradlew :dwp-auth-server:bootRun

# Health 확인
curl http://localhost:8001/actuator/health
```

### 문제 2: "Flyway migration failed"
**원인**: DB 스키마 불일치

**해결**:
```bash
# 1. Flyway 상태 확인
psql -h localhost -U dwp_user -d dwp_auth -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"

# 2. 필요 시 baseline 재생성 (개발 환경만!)
./gradlew :dwp-auth-server:flywayBaseline

# 3. 마이그레이션 재적용
./gradlew :dwp-auth-server:flywayMigrate
```

### 문제 3: "JWT signature verification failed"
**원인**: `JWT_SECRET` 불일치 (Python ↔ Java)

**해결**:
```bash
# JWT_SECRET 환경 변수 확인
echo $JWT_SECRET

# Aura Platform과 동일한지 확인
```

### 문제 4: "Missing required header X-Tenant-ID"
**원인**: Gateway에서 헤더 전파 실패

**해결**:
```bash
# Gateway 로그 확인
grep "X-Tenant-ID" logs/gateway.log

# Feign 헤더 전파 확인
grep "FeignHeaderInterceptor" logs/*.log
```

---

## 모니터링

### Prometheus 메트릭 수집
```bash
# 각 서비스의 /actuator/prometheus 엔드포인트를 Prometheus가 스크랩
# prometheus.yml 예시:
scrape_configs:
  - job_name: 'dwp-backend'
    static_configs:
      - targets:
        - 'localhost:8001'  # Auth
        - 'localhost:8081'  # Main
        # ...
```

### Grafana 대시보드 (향후)
- JVM 메트릭 (Heap, GC, Threads)
- HTTP 요청 메트릭 (Rate, Latency, Errors)
- DB 연결 풀 메트릭

---

## 롤백 절차

### 1. 코드 롤백
```bash
# Git 이전 커밋으로 롤백
git revert <commit-hash>
git push

# 재배포
./deploy.sh
```

### 2. DB 마이그레이션 롤백
```bash
# Flyway는 자동 rollback 미지원
# 각 마이그레이션 파일에 주석으로 rollback SQL을 기록해둬야 함

# 예시: V3__add_columns.sql에 주석으로 롤백 SQL 추가
-- Rollback:
-- ALTER TABLE sys_menus DROP COLUMN new_column;

# 수동 실행
psql -h localhost -U dwp_user -d dwp_auth -c "ALTER TABLE sys_menus DROP COLUMN new_column;"
```

---

## 배포 전 체크리스트

### 필수
- [ ] `./gradlew build` 통과
- [ ] `./gradlew test` 통과
- [ ] Flyway 마이그레이션 정상 적용 (로컬)
- [ ] Health Check 모두 UP
- [ ] 환경 변수 모두 설정 (특히 `SERVICE_*_URL`, `JWT_SECRET`)

### 권장
- [ ] 통합 테스트 (Testcontainers) 통과
- [ ] OpenAPI 문서 생성 확인 (`/v3/api-docs`)
- [ ] Correlation ID 로그 확인

---

## 참고
- [Flyway Baseline 전략](../specs/migrations/FLYWAY_BASELINE_STRATEGY.md)
- [Getting Started](../essentials/GETTING_STARTED_BACKEND.md)
- [Project Rules](../essentials/PROJECT_RULES_BACKEND.md)
