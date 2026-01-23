# DWP Backend Getting Started (빠른 시작)

## 1. 사전 요구사항
- Java 17
- PostgreSQL 15+
- Redis 7+
- Gradle 8.x (wrapper 포함)

## 2. 로컬 개발 환경 설정

### 2.1 데이터베이스 설정
```bash
# PostgreSQL 데이터베이스 생성
createdb dwp_auth
createdb dwp_main
```

### 2.2 환경 변수 설정
```bash
export DB_HOST=localhost
export DB_PORT=5432
export DB_USERNAME=dwp_user
export DB_PASSWORD=dwp_password
export JWT_SECRET=your_secret_key_at_least_32_characters
export REDIS_HOST=localhost
export REDIS_PORT=6379
```

### 2.3 빌드 및 실행
```bash
# 전체 빌드
./gradlew build

# 서비스별 실행
./gradlew :dwp-gateway:bootRun         # Port 8080
./gradlew :dwp-auth-server:bootRun     # Port 8001
./gradlew :dwp-main-service:bootRun    # Port 8081
```

## 3. 헬스 체크
```bash
curl http://localhost:8080/api/auth/health
curl http://localhost:8080/api/main/health
```

## 4. 다음 단계
- [프로젝트 규칙](./PROJECT_RULES_BACKEND.md)
- [로컬 개발 가이드](./LOCAL_DEV_GUIDE_BACKEND.md)
- [배포 가이드](./DEPLOYMENT_GUIDE_BACKEND.md)
