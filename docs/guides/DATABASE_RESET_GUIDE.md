# 데이터베이스 초기화 가이드

**작성일**: 2026-01-21  
**목적**: 개발 환경에서 데이터베이스를 쉽게 초기화하는 방법

---

## 🎯 가장 간단한 방법 (권장)

### 방법 1: 자동화 스크립트 사용 ⭐

```bash
# 프로젝트 루트에서 실행
./scripts/reset-db.sh
```

**동작**:
1. 기존 데이터베이스 삭제 확인
2. 새 데이터베이스 생성
3. 애플리케이션 시작 시 Flyway가 자동으로 마이그레이션 실행

**옵션**:
```bash
# Flyway 히스토리만 삭제 (데이터베이스는 유지)
./scripts/reset-db.sh --flyway-only

# 데이터베이스 삭제 건너뛰기 (이미 삭제된 경우)
./scripts/reset-db.sh --skip-drop
```

---

## 방법 2: 애플리케이션 시작 시 자동 실행

**가장 간단**: 데이터베이스만 삭제하고 재생성하면, 애플리케이션 시작 시 Flyway가 자동으로 마이그레이션을 실행합니다.

```bash
# 1. 데이터베이스 삭제 및 재생성
psql -U dwp_user -d postgres -c "DROP DATABASE IF EXISTS dwp_auth;"
psql -U dwp_user -d postgres -c "CREATE DATABASE dwp_auth;"
psql -U dwp_user -d dwp_auth -c "GRANT ALL PRIVILEGES ON DATABASE dwp_auth TO dwp_user;"

# 2. 애플리케이션 시작 (Flyway가 자동으로 마이그레이션 실행)
./gradlew :dwp-auth-server:bootRun
```

**장점**: 
- 별도 마이그레이션 명령 불필요
- 애플리케이션 시작 시 자동 실행

---

## 방법 3: 수동 실행

### 3.1 데이터베이스 완전 초기화

```bash
# PostgreSQL에 접속
psql -U dwp_user -d postgres

# 기존 데이터베이스 삭제
DROP DATABASE IF EXISTS dwp_auth;

# 새 데이터베이스 생성
CREATE DATABASE dwp_auth;

# 권한 부여
GRANT ALL PRIVILEGES ON DATABASE dwp_auth TO dwp_user;
\q
```

### 3.2 애플리케이션 시작

```bash
./gradlew :dwp-auth-server:bootRun
```

애플리케이션이 시작되면 Flyway가 자동으로 `V1__create_iam_schema.sql`을 실행합니다.

---

## 방법 4: Flyway 히스토리만 초기화

기존 데이터베이스는 유지하고 Flyway 히스토리만 삭제하는 경우:

```bash
# Flyway 히스토리 삭제
psql -U dwp_user -d dwp_auth -c "DROP TABLE IF EXISTS flyway_schema_history;"

# 애플리케이션 시작 (Flyway가 마이그레이션 재실행)
./gradlew :dwp-auth-server:bootRun
```

---

## 환경 변수 설정

스크립트는 다음 환경 변수를 사용합니다 (기본값 제공):

```bash
export DB_HOST=localhost        # 기본값: localhost
export DB_PORT=5432             # 기본값: 5432
export DB_NAME=dwp_auth         # 기본값: dwp_auth
export DB_USERNAME=dwp_user     # 기본값: dwp_user
export DB_PASSWORD=dwp_password # 기본값: dwp_password
```

**사용 예시**:
```bash
DB_HOST=localhost DB_PORT=5432 ./scripts/reset-db.sh
```

---

## 확인 방법

### 데이터베이스 초기화 확인

```bash
# 데이터베이스 연결 확인
psql -U dwp_user -d dwp_auth -c "\dt"

# Flyway 히스토리 확인
psql -U dwp_user -d dwp_auth -c "SELECT * FROM flyway_schema_history;"

# 테이블 개수 확인
psql -U dwp_user -d dwp_auth -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public';"
```

**예상 결과**:
- 테이블 수: 약 20개 이상
- Flyway 히스토리: V1 기록 1건

### 애플리케이션 로그 확인

애플리케이션 시작 시 다음과 같은 로그가 출력됩니다:

```
Flyway Migrate: Successfully applied 1 migration
```

---

## 문제 해결

### 오류: "database does not exist"

```bash
# 데이터베이스 생성
psql -U dwp_user -d postgres -c "CREATE DATABASE dwp_auth;"
```

### 오류: "permission denied"

```bash
# 권한 부여
psql -U dwp_user -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE dwp_auth TO dwp_user;"
```

### 오류: "Flyway checksum mismatch"

```bash
# Flyway 히스토리 삭제 후 재실행
psql -U dwp_user -d dwp_auth -c "DROP TABLE IF EXISTS flyway_schema_history;"
./gradlew :dwp-auth-server:bootRun
```

---

## 주의사항

### ⚠️ 개발 환경에서만 사용

- **프로덕션 환경에서는 절대 사용하지 마세요**
- 데이터베이스 삭제 시 모든 데이터가 손실됩니다
- 백업이 필요하면 먼저 백업하세요

### ✅ 안전한 사용 방법

1. **데이터 백업** (필요시):
   ```bash
   pg_dump -U dwp_user -d dwp_auth > backup_$(date +%Y%m%d_%H%M%S).sql
   ```

2. **확인 후 실행**: 스크립트는 실행 전 확인 메시지를 표시합니다

---

## 요약

**가장 간단한 방법**:
```bash
./scripts/reset-db.sh
./gradlew :dwp-auth-server:bootRun
```

이 두 명령만 실행하면 데이터베이스가 완전히 초기화되고 애플리케이션이 시작됩니다!

---

**작성일**: 2026-01-21  
**작성자**: DWP Backend Team
