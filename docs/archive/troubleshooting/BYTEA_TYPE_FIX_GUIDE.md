# bytea 타입 오류 해결 가이드

## 문제 상황

```
ERROR: function lower(bytea) does not exist
```

사용자 목록 조회 API 호출 시 위 오류가 발생합니다.

## 원인

PostgreSQL 데이터베이스의 특정 컬럼이 `VARCHAR` 대신 `bytea` 타입으로 저장되어 있어, Hibernate가 생성한 SQL의 `LOWER()` 함수를 사용할 수 없습니다.

## 해결 방법

### 방법 1: Flyway 자동 마이그레이션 (권장)

1. **서버 재시작**
   ```bash
   cd /Users/joonbinchoi/Work/dwp/dwp-backend
   ./gradlew :dwp-auth-server:bootRun
   ```

2. **마이그레이션 실행 확인**
   
   서버 로그에서 다음 메시지를 확인:
   ```
   Migrating schema "public" to version "20 - fix bytea columns"
   Successfully applied 1 migration to schema "public"
   ```

3. **재테스트**
   ```bash
   curl "http://localhost:8080/api/admin/users?page=1&size=10"
   ```

### 방법 2: 수동 SQL 실행 (Flyway 실패 시)

**중요**: PostgreSQL에 직접 접속할 수 있어야 합니다.

#### 2-1. PostgreSQL 접속

```bash
# 로컬 PostgreSQL
psql -h localhost -U dwp_user -d dwp_auth

# Docker 컨테이너
docker exec -it <postgres_container_name> psql -U dwp_user -d dwp_auth
```

#### 2-2. 컬럼 타입 확인

```sql
SELECT 
    table_name, 
    column_name, 
    data_type, 
    character_maximum_length
FROM information_schema.columns 
WHERE table_name IN ('com_users', 'com_user_accounts')
AND column_name IN ('display_name', 'email', 'principal', 'provider_type', 'status')
ORDER BY table_name, column_name;
```

`bytea` 타입이 보이면 계속 진행합니다.

#### 2-3. 수동 변환 스크립트 실행

```bash
# 프로젝트 루트에서
psql -h localhost -U dwp_user -d dwp_auth -f manual_fix_bytea.sql
```

또는 PostgreSQL 콘솔에서 직접:

```sql
-- com_users.display_name
ALTER TABLE com_users 
ALTER COLUMN display_name TYPE VARCHAR(200) 
USING encode(display_name, 'escape')::VARCHAR;

-- com_users.email
ALTER TABLE com_users 
ALTER COLUMN email TYPE VARCHAR(255) 
USING encode(email, 'escape')::VARCHAR;

-- com_user_accounts.principal
ALTER TABLE com_user_accounts 
ALTER COLUMN principal TYPE VARCHAR(255) 
USING encode(principal, 'escape')::VARCHAR;

-- com_user_accounts.provider_type
ALTER TABLE com_user_accounts 
ALTER COLUMN provider_type TYPE VARCHAR(20) 
USING encode(provider_type, 'escape')::VARCHAR;

-- com_user_accounts.status
ALTER TABLE com_user_accounts 
ALTER COLUMN status TYPE VARCHAR(20) 
USING encode(status, 'escape')::VARCHAR;
```

#### 2-4. Flyway 히스토리 업데이트 (수동 실행한 경우)

```sql
INSERT INTO flyway_schema_history (
    installed_rank, 
    version, 
    description, 
    type, 
    script, 
    checksum, 
    installed_by, 
    installed_on, 
    execution_time, 
    success
) VALUES (
    (SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history),
    '20',
    'fix bytea columns',
    'SQL',
    'V20__fix_bytea_columns.sql',
    NULL,
    CURRENT_USER,
    CURRENT_TIMESTAMP,
    0,
    true
);
```

#### 2-5. 서버 재시작

```bash
# 서버를 종료하고 재시작
./gradlew :dwp-auth-server:bootRun
```

### 방법 3: 임시 우회 (급할 때)

키워드 검색을 비활성화하여 오류를 회피합니다. (비권장)

## 검증

### 1. 컬럼 타입 확인

```sql
SELECT 
    table_name, 
    column_name, 
    data_type
FROM information_schema.columns 
WHERE table_name IN ('com_users', 'com_user_accounts')
AND column_name IN ('display_name', 'email', 'principal')
ORDER BY table_name, column_name;
```

**기대 결과**: 모든 컬럼의 `data_type`이 `character varying` (VARCHAR)

### 2. API 테스트

```bash
# 키워드 검색 없이
curl "http://localhost:8080/api/admin/users?page=1&size=10"

# 키워드 검색 포함
curl "http://localhost:8080/api/admin/users?page=1&size=10&keyword=admin"
```

**기대 결과**: HTTP 200 OK

### 3. 로그 확인

서버 로그에서 `ERROR: function lower(bytea)` 오류가 더 이상 발생하지 않아야 합니다.

## 트러블슈팅

### Flyway 마이그레이션이 실행되지 않는 경우

1. **Flyway 설정 확인**
   
   `application.yml`:
   ```yaml
   spring:
     flyway:
       enabled: true
       locations: classpath:db/migration
       baseline-on-migrate: true
   ```

2. **기존 마이그레이션 상태 확인**
   ```sql
   SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
   ```

3. **마이그레이션 파일 존재 확인**
   ```bash
   ls -la dwp-auth-server/src/main/resources/db/migration/V20__fix_bytea_columns.sql
   ```

### bytea 타입으로 다시 돌아가는 경우

원인: JPA/Hibernate 엔티티 설정 문제

해결:
1. `User` 엔티티 확인
2. `@Column(columnDefinition = "VARCHAR(200)")` 명시적 지정
3. `application.yml`에서 `spring.jpa.hibernate.ddl-auto: validate` 확인

## 관련 파일

- Flyway 마이그레이션: `dwp-auth-server/src/main/resources/db/migration/V20__fix_bytea_columns.sql`
- 수동 수정 스크립트: `manual_fix_bytea.sql`
- Repository: `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/UserRepository.java`

## 참고

- Flyway 공식 문서: https://flywaydb.org/documentation/
- PostgreSQL 타입 변환: https://www.postgresql.org/docs/current/sql-altertable.html
