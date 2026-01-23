# P0-2 IAM 구현 진행 상황

> **작업일**: 2026-01-19  
> **상태**: 진행 중 (60% 완료)

---

## ✅ 완료된 작업

### 1. 현황 분석 (완료)
- **문서**: `docs/P0-2_STATUS_ANALYSIS.md`
- **결과**:
  - 스키마 관리: ddl-auto (Flyway 미사용)
  - 로그인 검증: hardcoded (DB 조회 없음)
  - 테이블: 없음 (dwp_auth DB 비어있음)
  - ApiResponse/ErrorCode: 일관 적용됨

### 2. Flyway 마이그레이션 설정 (완료)
- **파일**: `dwp-auth-server/build.gradle`
  - `flyway-core`, `flyway-database-postgresql` 의존성 추가
- **파일**: `dwp-auth-server/src/main/resources/application.yml`
  - Flyway 활성화
  - `ddl-auto: validate` (Flyway가 스키마 관리)
  - `baseline-on-migrate: true`

### 3. IAM 스키마 생성 (완료)
- **파일**: `dwp-auth-server/src/main/resources/db/migration/V1__create_iam_schema.sql`
- **테이블 17개 생성**:
  1. `com_tenants` - 테넌트 마스터
  2. `com_departments` - 부서/조직도
  3. `com_users` - 사용자 프로필
  4. `com_user_accounts` - 로그인 계정 (LOCAL/SSO)
  5. `sys_auth_policies` - 테넌트별 로그인 정책
  6. `sys_identity_providers` - SSO IdP 설정
  7. `com_roles` - 권한그룹/역할
  8. `com_role_members` - 역할 할당
  9. `com_resources` - 리소스 (메뉴/버튼/섹션/API)
  10. `com_permissions` - 권한 행위
  11. `com_role_permissions` - 역할-리소스-권한 매핑
  12. `com_audit_logs` - 감사 로그
  13. `sys_user_sessions` - 세션/강제 로그아웃
  14. `sys_login_histories` - 로그인 이력
  15. `sys_api_call_histories` - API 호출 이력
  16. `sys_page_view_events` - PV/UV Raw 이벤트
  17. `sys_page_view_daily_stats` - PV/UV 집계

- **특징**:
  - 모든 테이블에 COMMENT 포함
  - DB 레벨 FK 제약 없음 (논리적 참조만)
  - 공통 기본 컬럼 (created_at, created_by, updated_at, updated_by)
  - 테넌트 기반 멀티테넌시 지원

### 4. Seed 데이터 생성 (완료)
- **파일**: `dwp-auth-server/src/main/resources/db/migration/V2__insert_seed_data.sql`
- **데이터**:
  - **Tenant**: dev (tenant_id=1)
  - **Auth Policy**: LOCAL only, token TTL 3600s
  - **Departments**: HQ (1), Development (2)
  - **User**: admin@dev.local (user_id=1)
  - **Account**: admin/admin (LOCAL, BCrypt hash)
  - **Role**: ADMIN (role_id=1)
  - **Permissions**: VIEW, USE, EDIT, APPROVE, EXECUTE
  - **Resources**: 
    - Dashboard (menu.dashboard)
    - Mail (menu.mail, menu.mail.inbox, menu.mail.sent)
    - AI Workspace (menu.ai-workspace)
    - Buttons (btn.mail.send, btn.mail.delete)
  - **Role Permissions**: ADMIN role has full access to all resources

### 5. Entity 클래스 생성 (완료)
- **디렉토리**: `dwp-auth-server/src/main/java/com/dwp/services/auth/entity/`
- **Entity 9개**:
  1. `BaseEntity` - 공통 기본 엔티티 (Auditing)
  2. `Tenant` - 테넌트
  3. `User` - 사용자
  4. `UserAccount` - 로그인 계정
  5. `Role` - 역할
  6. `Resource` - 리소스
  7. `Permission` - 권한
  8. `RolePermission` - 역할-권한 매핑
  9. `RoleMember` - 역할 할당

- **특징**:
  - JPA Auditing 활성화 (`@EnableJpaAuditing`)
  - Lombok 사용 (@Entity, @Getter, @Setter, @Builder)
  - 논리적 참조 (FK 제약 없음)

### 6. Repository 인터페이스 생성 (완료)
- **디렉토리**: `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/`
- **Repository 6개**:
  1. `UserRepository` - 사용자 조회
  2. `UserAccountRepository` - 로그인 계정 조회
  3. `RoleMemberRepository` - 역할 할당 조회
  4. `RolePermissionRepository` - 역할-권한 매핑 조회
  5. `ResourceRepository` - 리소스 조회
  6. `PermissionRepository` - 권한 조회

- **특징**:
  - Spring Data JPA 사용
  - 테넌트 기반 조회 메서드 포함
  - 권한 조회 최적화 (JOIN 쿼리)

### 7. Security 설정 추가 (완료)
- **파일**: `dwp-auth-server/src/main/java/com/dwp/services/auth/config/SecurityConfig.java`
- **내용**:
  - `BCryptPasswordEncoder` 빈 등록
  - LOCAL 계정 비밀번호 해싱/검증용

---

## 🚧 진행 중 / 대기 중인 작업

### 8. AuthService 리팩토링 (진행 중)
- **목표**: DB 기반 LOCAL 인증 구현
- **작업 내용**:
  - UserAccountRepository를 통한 DB 조회
  - BCryptPasswordEncoder를 사용한 비밀번호 검증
  - 테넌트별 사용자 조회 및 권한 확인
  - 로그인 이력 기록 (sys_login_histories)

### 9. 새 API 추가 (대기 중)
- **GET /api/auth/me**: 내 정보 조회
  - JWT 토큰 기반 사용자 정보 반환
  - 응답: userId, tenantId, displayName, email, roles
- **GET /api/auth/permissions**: 내 권한 목록 조회
  - JWT 토큰 기반 권한 조회
  - 응답: resource.type, resource.key, permission.code, effect

### 10. 테스트 코드 작성 (대기 중)
- 로그인 성공 테스트 (BCrypt 검증 + JWT 발급)
- 로그인 실패 테스트 (401 + ApiResponse errorCode)
- 권한 조회 API 테스트 (admin 계정)

### 11. 문서 작성 및 README 업데이트 (대기 중)
- P0-2 구현 요약 문서
- API 명세 (요청/응답 예시)
- 실행 방법 (curl)
- README.md 업데이트

---

## 📊 진행률

- **완료**: 7/11 작업 (64%)
- **진행 중**: 1/11 작업 (9%)
- **대기 중**: 3/11 작업 (27%)

---

## 🔧 빌드 상태

- **컴파일**: ✅ 성공
- **경고**: 5개 (Lombok @Builder 관련, 기능에는 문제없음)
- **마이그레이션**: 미실행 (서버 시작 시 자동 실행)

---

## 📝 다음 단계

1. AuthService 리팩토링 완료
2. 새 API 추가 (GET /api/auth/me, GET /api/auth/permissions)
3. 테스트 코드 작성 및 실행
4. 서버 시작 및 마이그레이션 확인
5. curl로 API 테스트
6. 문서 작성 및 README 업데이트
7. 커밋 및 푸시

---

## 🎯 이번 PR 목표 (재확인)

- [x] IAM 스키마 17개 테이블 생성
- [x] Seed 데이터 (admin/admin 계정)
- [x] Entity 및 Repository 생성
- [ ] DB 기반 LOCAL 로그인 구현
- [ ] 최소 권한 조회 API 제공
- [ ] 테스트 코드 작성
- [ ] 문서 작성

**진행률**: 60% 완료
