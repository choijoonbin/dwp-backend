# Flyway 마이그레이션 문제 해결 가이드

## 문제 상황

애플리케이션 재기동 후 마이그레이션이 정상적으로 실행되었는지 확인이 필요한 경우

## 확인 방법

### 1. 애플리케이션 로그에서 Flyway 메시지 확인

애플리케이션 시작 시 다음과 같은 로그가 출력되어야 합니다:

```
INFO  ... Flyway : Flyway Community Edition ...
INFO  ... Flyway : Database: jdbc:postgresql://localhost:5432/dwp_auth (PostgreSQL 15.x)
INFO  ... Flyway : Successfully validated X migrations
INFO  ... Flyway : Current version of schema "public": X
INFO  ... Flyway : Migrating schema "public" to version "3 - add admin menu resources"
INFO  ... Flyway : Successfully applied 1 migration to schema "public"
```

**에러가 발생한 경우:**
```
ERROR ... Flyway : Migration of schema "public" to version "3 - add admin menu resources" failed!
```

### 2. 데이터베이스 직접 확인

`docs/troubleshooting/CHECK_MIGRATION_STATUS.sql` 파일의 쿼리를 실행하여 확인:

```bash
# PostgreSQL에 접속
psql -h localhost -U dwp_user -d dwp_auth

# 또는 애플리케이션 로그에서 확인
```

**확인 항목:**
1. `flyway_schema_history` 테이블에서 V3 마이그레이션 실행 여부 확인
2. `sys_menus` 테이블에 새 메뉴 3개가 추가되었는지 확인
3. `com_resources` 테이블에 새 리소스 3개가 추가되었는지 확인
4. `com_role_permissions` 테이블에 권한이 부여되었는지 확인

### 3. 수동 마이그레이션 실행 (필요시)

Flyway가 자동으로 실행되지 않은 경우:

```bash
# Gradle을 통한 수동 실행
./gradlew flywayMigrate

# 또는 직접 SQL 실행
psql -h localhost -U dwp_user -d dwp_auth -f dwp-auth-server/src/main/resources/db/migration/V3__add_admin_menu_resources.sql
```

## 일반적인 문제 및 해결 방법

### 문제 1: 마이그레이션이 실행되지 않음

**원인:**
- Flyway가 비활성화되어 있음
- 마이그레이션 파일이 잘못된 위치에 있음
- 데이터베이스 연결 실패

**해결:**
1. `application.yml`에서 `spring.flyway.enabled: true` 확인
2. 마이그레이션 파일이 `src/main/resources/db/migration/` 디렉토리에 있는지 확인
3. 데이터베이스 연결 설정 확인

### 문제 2: 마이그레이션 실패 (SQL 에러)

**원인:**
- SQL 문법 오류
- 제약 조건 위반
- 데이터 타입 불일치

**해결:**
1. 애플리케이션 로그에서 정확한 에러 메시지 확인
2. SQL 문법 검증 (특히 ON CONFLICT 구문)
3. 데이터베이스 상태 확인

### 문제 3: 마이그레이션은 성공했지만 데이터가 없음

**원인:**
- INSERT 문이 실행되지 않음
- WHERE 조건이 맞지 않음
- 트랜잭션 롤백

**해결:**
1. `flyway_schema_history`에서 마이그레이션 성공 여부 확인
2. 수동으로 SQL 쿼리 실행하여 데이터 확인
3. 트랜잭션 로그 확인

## 빠른 확인 명령어

```sql
-- 1. 마이그레이션 히스토리 확인
SELECT version, description, installed_on, success 
FROM flyway_schema_history 
ORDER BY installed_rank DESC 
LIMIT 5;

-- 2. 새 메뉴 확인
SELECT menu_key, menu_name, sort_order, is_visible 
FROM sys_menus 
WHERE tenant_id = 1 
  AND menu_key IN ('menu.admin.menus', 'menu.admin.codes', 'menu.admin.code-usages');

-- 3. 새 리소스 확인
SELECT key, name, enabled 
FROM com_resources 
WHERE tenant_id = 1 
  AND key IN ('menu.admin.menus', 'menu.admin.codes', 'menu.admin.code-usages');
```

## 다음 단계

마이그레이션이 성공적으로 실행되었다면:

1. Menu Tree API 테스트: `GET /api/auth/menus/tree`
2. Admin 메뉴가 사이드바에 표시되는지 확인
3. 각 메뉴 페이지 접근 테스트
