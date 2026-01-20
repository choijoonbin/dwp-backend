# 데이터베이스 연결 정보 요약

## PostgreSQL

### 기본 연결 정보
- **호스트**: `localhost`
- **포트**: `5432`
- **사용자명**: `dwp_user`
- **비밀번호**: `dwp_password`

### 데이터베이스별 연결 정보

#### 1. Auth Server (dwp_auth)
```
URL: jdbc:postgresql://localhost:5432/dwp_auth
Username: dwp_user
Password: dwp_password
```

#### 2. Main Service (dwp_main)
```
URL: jdbc:postgresql://localhost:5432/dwp_main
Username: dwp_user
Password: dwp_password
```

#### 3. Mail Service (dwp_mail)
```
URL: jdbc:postgresql://localhost:5432/dwp_mail
Username: dwp_user
Password: dwp_password
```

#### 4. Chat Service (dwp_chat)
```
URL: jdbc:postgresql://localhost:5432/dwp_chat
Username: dwp_user
Password: dwp_password
```

#### 5. Approval Service (dwp_approval)
```
URL: jdbc:postgresql://localhost:5432/dwp_approval
Username: dwp_user
Password: dwp_password
```

## Redis

### 연결 정보
- **호스트**: `localhost`
- **포트**: `6379`
- **비밀번호**: 없음 (로컬 개발 환경)

### 사용 목적
- 채팅 서비스: 실시간 메시지 캐싱 및 세션 관리
- 향후 확장: 세션 스토어, 캐시 등

## Docker Compose 명령어

```bash
# 인프라 시작
docker-compose up -d

# 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs -f postgres
docker-compose logs -f redis

# 중지
docker-compose down

# 데이터까지 완전 삭제
docker-compose down -v
```

## 데이터베이스 접속 방법

### PostgreSQL CLI
```bash
# Auth Server DB 접속
docker exec -it dwp-postgres psql -U dwp_user -d dwp_auth

# Main Service DB 접속
docker exec -it dwp-postgres psql -U dwp_user -d dwp_main

# 모든 데이터베이스 목록 확인
docker exec -it dwp-postgres psql -U dwp_user -c "\l"
```

### Redis CLI
```bash
# Redis 접속
docker exec -it dwp-redis redis-cli

# 연결 테스트
docker exec -it dwp-redis redis-cli ping
```

## 주의사항

1. **로컬 개발 환경**: 위 정보는 로컬 개발 환경용입니다.
2. **프로덕션 환경**: 프로덕션에서는 각 서비스별로 독립적인 데이터베이스 인스턴스를 사용하거나, 최소한 별도의 사용자 계정을 사용해야 합니다.
3. **보안**: 프로덕션 환경에서는 강력한 비밀번호를 사용하고, 네트워크 접근을 제한해야 합니다.
