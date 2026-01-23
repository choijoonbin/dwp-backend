# Roles 화면 대응 스키마 보완 완료 요약

## 작성일
2026-01-21

## 완료 사항

### 1. 스키마 갭 분석 문서 작성 ✅
- `docs/PR_ROLES_SCHEMA_GAP_REVIEW.md` 생성
- 현재 스키마 현황 및 부족한 컬럼/필드 명확히 식별

### 2. Flyway 마이그레이션 생성 ✅
- `V2__add_roles_schema_enhancements.sql` 생성
- `com_roles.status` 추가 (ACTIVE/INACTIVE)
- `com_permissions.sort_order` + `description` 추가
- `com_resources.sort_order` 추가
- `ROLE_STATUS` 코드 그룹 추가 및 seed 데이터 삽입

### 3. 엔티티 필드 추가 ✅
- `Role.status` 필드 추가 (VARCHAR(20), 기본값 "ACTIVE")
- `Permission.sortOrder` + `description` 필드 추가
- `Resource.sortOrder` 필드 추가

### 4. API 응답 구조 개선 ✅

#### RoleSummary / RoleDetail DTO
- `id: String` 추가 (comRoleId를 문자열로 변환)
- `status: String` 추가 (실제 DB 값 사용)
- `memberCount`, `userCount`, `departmentCount` 추가 (분리 계산)

#### RoleMemberView DTO
- `subjectEmail` 추가 (USER일 경우 email)
- `departmentName` 추가 (USER의 primary department name)

#### RolePermissionView DTO
- `permissionSortOrder` 추가 (매트릭스 컬럼 정렬)
- `permissionDescription` 추가 (툴팁용)
- `resourceSortOrder` 추가 (리소스 트리 정렬)

### 5. 서비스 로직 개선 ✅

#### RoleQueryService
- `getRoles()`: status 필터 파라미터 추가, 실제 status 값 사용
- `getRoles()`: memberCount/userCount/departmentCount 분리 계산
- `getRoleDetail()`: 실제 status 값 사용, 멤버 수 분리 계산
- `getRoleMembers()`: subjectEmail, departmentName 포함
- `getRolePermissions()`: sortOrder 기반 정렬 로직 추가

#### RoleCommandService
- `createRole()`: status 기본값 "ACTIVE" 설정
- `updateRole()`: status 업데이트 지원 + CodeResolver 검증

#### RoleRepository
- `findByTenantIdAndKeyword()`: status 필터 파라미터 추가

### 6. API 엔드포인트 개선 ✅

#### GET /api/admin/roles
- `status` 쿼리 파라미터 추가 (필터링)
- 응답에 `id`, `status`, `memberCount`, `userCount`, `departmentCount` 포함

#### GET /api/admin/roles/{roleId}
- 응답에 `id`, `status`, `memberCount`, `userCount`, `departmentCount` 포함

#### GET /api/admin/roles/{roleId}/members
- 응답에 `subjectEmail`, `departmentName` 포함

#### GET /api/admin/roles/{roleId}/permissions
- 응답에 `permissionSortOrder`, `permissionDescription`, `resourceSortOrder` 포함
- 정렬: resource sort_order ASC, permission sort_order ASC

---

## 변경된 파일 목록

### DB 마이그레이션
- `dwp-auth-server/src/main/resources/db/migration/V2__add_roles_schema_enhancements.sql` (신규)

### 엔티티
- `dwp-auth-server/src/main/java/com/dwp/services/auth/entity/Role.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/entity/Permission.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/entity/Resource.java`

### DTO
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/RoleSummary.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/RoleDetail.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/RoleMemberView.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/RolePermissionView.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/UpdateRoleRequest.java`

### Repository
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/RoleRepository.java`

### Service
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/roles/RoleQueryService.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/roles/RoleCommandService.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/roles/RoleManagementService.java`

### Controller
- `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/RoleController.java`

### 문서
- `docs/PR_ROLES_SCHEMA_GAP_REVIEW.md` (신규)
- `docs/PR_ROLES_SCHEMA_ENHANCEMENT_SUMMARY.md` (신규)

---

## 다음 단계 (선택 사항)

### 감사로그 강화 (Pending)
- Bulk 권한 변경 시 diff 추적 로직 추가
- 변경된 항목만 식별 가능하도록 before/after JSON 구조 개선
- 변경 실패 케이스 원인 기록 강화

### 운영 필드 추가 (선택)
- `com_role_members.enabled` + `revoked_at` (멤버 할당 이력)
- `com_role_permissions.enabled` (권한 활성화/비활성화)

---

## 테스트 필요 사항

1. Flyway 마이그레이션 실행 확인
2. 기존 데이터 status 기본값 "ACTIVE" 설정 확인
3. API 응답 구조 프론트엔드 타입과 일치 확인
4. status 필터링 동작 확인
5. memberCount/userCount/departmentCount 계산 정확성 확인
6. RolePermission 정렬 순서 확인

---

## 참고

- 프론트엔드 타입 정의: `libs/shared-utils/src/admin/types.ts`
- 프론트엔드 요청 문서: `/Users/joonbinchoi/Work/dwp/dwp-frontend/docs/BACKEND_API_ROLES_RESPONSE_FIX.md`
