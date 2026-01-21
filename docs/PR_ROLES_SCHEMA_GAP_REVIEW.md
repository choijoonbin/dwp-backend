# Roles 화면 대응 스키마 갭 분석

## 작성일
2026-01-21

## 목적
프론트엔드 Roles 화면(운영급 UI) 구현을 위한 현재 스키마 점검 및 부족한 컬럼/응답 필드 식별

---

## 1. 현재 스키마 현황

### 1.1 com_roles 테이블
**현재 컬럼:**
- `role_id`, `tenant_id`, `code`, `name`, `description`
- `created_at`, `created_by`, `updated_at`, `updated_by`

**부족한 컬럼:**
- ❌ `status` (ACTIVE/INACTIVE) - 역할 활성화/비활성화 구분 불가
- ❌ `deleted_at` (soft delete) - 삭제 이력 추적 불가

### 1.2 com_role_members 테이블
**현재 컬럼:**
- `role_member_id`, `tenant_id`, `role_id`, `subject_type`, `subject_id`
- `created_at`, `created_by`, `updated_at`, `updated_by`

**부족한 컬럼:**
- ❌ `enabled` (BOOLEAN) - 멤버 할당 활성화/비활성화 구분 불가
- ❌ `revoked_at` (TIMESTAMP) - 해제 이력 추적 불가

### 1.3 com_role_permissions 테이블
**현재 컬럼:**
- `role_permission_id`, `tenant_id`, `role_id`, `resource_id`, `permission_id`, `effect`
- `created_at`, `created_by`, `updated_at`, `updated_by`

**부족한 컬럼:**
- ❌ `enabled` (BOOLEAN) - 권한 활성화/비활성화 구분 불가
- ❌ `sort_order` (INTEGER) - 매트릭스 정렬 불가

### 1.4 com_permissions 테이블
**현재 컬럼:**
- `permission_id`, `code`, `name`
- `created_at`, `created_by`, `updated_at`, `updated_by`

**부족한 컬럼:**
- ❌ `sort_order` (INTEGER) - 매트릭스 컬럼 순서 고정 불가
- ❌ `description` (TEXT) - 툴팁/설명 제공 불가

### 1.5 com_resources 테이블
**현재 컬럼:**
- `resource_id`, `tenant_id`, `type`, `key`, `name`, `parent_resource_id`, `enabled`
- `created_at`, `created_by`, `updated_at`, `updated_by`

**부족한 컬럼:**
- ❌ `sort_order` (INTEGER) - 리소스 트리 정렬 불가 (현재는 name/key 기준 정렬)

---

## 2. API 응답 구조 갭

### 2.1 GET /api/admin/roles 응답
**부족한 필드:**
- ❌ `status` - 현재 하드코딩 "ACTIVE"만 반환
- ❌ `memberCount` - 현재 계산하지만 departmentCount 분리 없음
- ❌ `departmentCount` - 부서 할당 수 별도 제공 없음

### 2.2 GET /api/admin/roles/{roleId}/members 응답
**부족한 필드:**
- ❌ `subjectName` - USER일 경우 displayName, DEPARTMENT일 경우 name 미제공
- ❌ `subjectEmail` - USER일 경우 email 미제공
- ❌ `departmentName` - USER의 primary_department_id 기반 부서명 미제공

### 2.3 GET /api/admin/roles/{roleId}/permissions 응답
**부족한 구조:**
- ❌ Resources 트리 구조 미제공 (현재는 flat list)
- ❌ Permissions 정렬 순서 미제공 (sort_order 없음)
- ❌ 매트릭스 구성에 필요한 리소스-권한 조합 구조 미제공

---

## 3. 감사로그 갭

**부족한 추적 정보:**
- ❌ Bulk 권한 변경 시 diff 추적 불가 (before/after 전체만 기록)
- ❌ 변경된 항목만 식별 불가 (DENY→ALLOW 같은 변경 추적 어려움)
- ❌ 변경 실패 케이스 원인 기록 미흡

---

## 4. 우선순위별 보완 필요 사항

### 필수 (P0)
1. `com_roles.status` 추가 - 역할 활성화/비활성화 필수
2. API 응답 `status` 필드 실제 값 반영
3. `memberCount` / `departmentCount` 분리 계산

### 권장 (P1)
4. `com_permissions.sort_order` + `description` 추가 - 매트릭스 정렬/설명
5. `com_resources.sort_order` 추가 - 리소스 트리 정렬
6. RoleMember API 응답에 `subjectName`/`subjectEmail`/`departmentName` 포함

### 선택 (P2)
7. `com_role_members.enabled` + `revoked_at` - 멤버 할당 이력
8. `com_role_permissions.enabled` - 권한 활성화/비활성화
9. 감사로그 diff 추적 강화

---

## 5. 영향 범위

- **DB 스키마 변경**: Flyway 마이그레이션 필요
- **엔티티 변경**: Role, Permission, Resource 엔티티 필드 추가
- **서비스 로직 변경**: RoleQueryService, RoleCommandService 수정
- **API 응답 변경**: RoleSummary, RoleDetail, RoleMemberView DTO 수정
- **감사로그 강화**: AuditLogService diff 추적 로직 추가
