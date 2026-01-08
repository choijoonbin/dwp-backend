# 데이터베이스 설정 완료

## ✅ 생성된 데이터베이스

다음 5개의 데이터베이스가 모두 생성되었습니다:

1. **dwp_auth** - Auth Server 전용
2. **dwp_main** - Main Service 전용
3. **dwp_mail** - Mail Service 전용
4. **dwp_chat** - Chat Service 전용
5. **dwp_approval** - Approval Service 전용

## 확인 방법

```bash
# 모든 데이터베이스 목록 확인
docker exec -it dwp-postgres psql -U dwp_user -c "\l" | grep dwp

# 특정 데이터베이스 연결 테스트
docker exec -it dwp-postgres psql -U dwp_user -d dwp_auth -c "SELECT current_database();"
```

## 다음 단계

이제 모든 서비스를 실행할 수 있습니다:

```bash
# Auth Server
./gradlew :dwp-auth-server:bootRun

# Main Service
./gradlew :dwp-main-service:bootRun

# 기타 서비스들...
```

## 문제 해결

### 데이터베이스가 없는 경우

```bash
# Docker Compose 재시작 (볼륨 포함 삭제)
docker-compose down -v
docker-compose up -d

# 수동으로 데이터베이스 생성
docker exec -it dwp-postgres psql -U dwp_user -d postgres -c "CREATE DATABASE dwp_auth;"
```
