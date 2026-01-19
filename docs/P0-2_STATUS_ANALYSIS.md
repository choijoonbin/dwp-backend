# P0-2 현황 분석 결과

> **분석일**: 2026-01-19  
> **대상**: dwp-auth-server IAM 구현 현황

---

## 0) 현황 분석 (코드/DB 기준)

### [Schema Mgmt]
**스키마 관리 방식**: `ddl-auto: update` (JPA 자동 스키마 관리)

```yaml
# dwp-auth-server/src/main/resources/application.yml
jpa:
  hibernate:
    ddl-auto: ${JPA_DDL_AUTO:update}
```

**분석**:
- Flyway/Liquibase 등 마이그레이션 도구 **사용 안 함**
- JPA ddl-auto에 의존하여 Entity 클래스 기반으로 자동 스키마 생성
- **문제점**: 버전 관리, 롤백, Seed 데이터 관리 불가능

### [Login Verify]
**현재 검증 방식**: `hardcoded` (임시 구현)

```java
// AuthService.validateCredentials()
private boolean validateCredentials(String username, String password) {
    // 임시 구현: username과 password가 모두 비어있지 않으면 통과
    if (username == null || username.trim().isEmpty()) {
        return false;
    }
    if (password == null || password.trim().isEmpty()) {
        return false;
    }
    return true;  // ⚠️ 실제 검증 없음
}
```

**분석**:
- **DB 조회 없음**: UserRepository, UserAccount 관련 코드 없음
- **비밀번호 검증 없음**: BCrypt 등 해시 검증 로직 없음
- username을 그대로 userId로 사용 (JWT sub 클레임에 포함)
- 모든 로그인 시도 성공 (username/password가 비어있지 않으면)

### [Tables]
**dwp_auth DB 현재 테이블**: **없음**

```bash
$ docker exec dwp-postgres psql -U dwp_user -d dwp_auth -c "\dt"
Did not find any relations.
```

**분석**:
- dwp_auth DB는 생성되어 있으나 테이블이 하나도 없음
- Entity 클래스가 없어서 ddl-auto도 동작하지 않음

### [ApiResponse/ErrorCode/ExceptionHandler]
**현재 상태**: ✅ **일관 적용됨**

1. **ApiResponse<T>**:
   - dwp-core 모듈의 ApiResponse 사용
   - 모든 Controller 응답이 ApiResponse로 래핑됨
   - 예: `AuthController.login()` → `ApiResponse<LoginResponse>`

2. **ErrorCode**:
   - dwp-core 모듈의 ErrorCode enum 사용
   - BaseException과 연동
   - 예: `ErrorCode.AUTH_INVALID_CREDENTIALS`, `ErrorCode.UNAUTHORIZED`

3. **SecurityExceptionHandler**:
   - Spring Security 예외를 ApiResponse로 변환
   - 401/403 처리 (`AuthenticationEntryPoint`, `AccessDeniedHandler` 구현)
   - JSON 형식 응답 보장

---

## [Gaps] 이번 PR에 채울 핵심 공백 3가지

### 1. IAM 스키마 전체 부재
- 사용자 (com_users, com_user_accounts) 테이블 없음
- 권한 (com_roles, com_permissions, com_role_permissions) 테이블 없음
- 리소스 (com_resources) 테이블 없음
- 테넌트/부서 (com_tenants, com_departments) 테이블 없음
- 정책/로그 (sys_auth_policies, sys_login_histories 등) 테이블 없음

### 2. DB 기반 인증 로직 부재
- UserRepository, UserAccountRepository 없음
- BCryptPasswordEncoder 설정 없음
- LOCAL 계정 검증 로직 없음
- 다중 인증 방식 (LOCAL/SSO) 지원 구조 없음

### 3. 스키마 관리 도구 부재
- Flyway/Liquibase 미설정
- 마이그레이션 스크립트 없음
- Seed 데이터 관리 불가능
- 스키마 버전 관리 없음

---

## 이번 PR 목표

### A. 스키마 관리 도구 도입
- ✅ Flyway 의존성 추가
- ✅ 마이그레이션 스크립트 디렉토리 구조 생성
- ✅ ddl-auto를 validate로 변경 (Flyway가 스키마 관리)

### B. IAM 스키마 생성
- ✅ 17개 테이블 생성 (com_* 11개, sys_* 6개)
- ✅ 모든 테이블에 COMMENT 포함
- ✅ DB 레벨 FK 제약 없음 (논리적 참조만)
- ✅ 공통 기본 컬럼 (created_at, created_by, updated_at, updated_by)

### C. Seed 데이터 생성
- ✅ tenant: dev (default)
- ✅ admin user + LOCAL account (BCrypt password)
- ✅ admin role + permissions
- ✅ 기본 리소스 (dashboard, mail, ai-workspace 메뉴)

### D. DB 기반 인증 구현
- ✅ Entity 클래스 생성 (User, UserAccount, Role, Resource 등)
- ✅ Repository 인터페이스 작성
- ✅ BCryptPasswordEncoder 빈 설정
- ✅ AuthService 리팩토링 (DB 조회 + BCrypt 검증)

### E. 최소 권한 조회 API
- ✅ GET /api/auth/me (내 정보 조회)
- ✅ GET /api/auth/permissions (내 권한 목록)

---

## 다음 단계

1. Flyway 의존성 추가 및 설정
2. V1__create_iam_schema.sql 작성
3. V2__insert_seed_data.sql 작성
4. Entity 클래스 생성
5. Repository 작성
6. AuthService 리팩토링
7. 새 API 추가
8. 테스트 코드 작성
