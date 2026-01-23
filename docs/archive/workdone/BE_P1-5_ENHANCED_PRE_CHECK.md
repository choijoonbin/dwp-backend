# BE P1-5 (Enhanced): 사전 체크 결과

**작성일**: 2026-01-20  
**목적**: 현재 구현 상태 확인 및 부족한 부분 파악

---

## ✅ 확인 결과

### 1) Admin CRUD API 존재 여부

#### Users 관리
- ✅ GET /api/admin/users (목록 조회, keyword, departmentId, roleId, status 필터)
- ✅ POST /api/admin/users (생성)
- ✅ GET /api/admin/users/{comUserId} (상세 조회)
- ✅ PUT /api/admin/users/{comUserId} (수정)
- ✅ POST /api/admin/users/{comUserId}/status (상태 변경)
- ✅ DELETE /api/admin/users/{comUserId} (삭제)
- ✅ POST /api/admin/users/{comUserId}/reset-password (비밀번호 재설정)
- ✅ GET /api/admin/users/{comUserId}/roles (역할 조회)
- ✅ PUT /api/admin/users/{comUserId}/roles (역할 업데이트)
- ⚠️ 부족: idpProviderType 필터 (보강 필요)

#### Roles 관리
- ✅ GET /api/admin/roles (목록 조회, keyword 필터)
- ✅ POST /api/admin/roles (생성)
- ✅ GET /api/admin/roles/{comRoleId} (상세 조회)
- ✅ PUT /api/admin/roles/{comRoleId} (수정)
- ✅ DELETE /api/admin/roles/{comRoleId} (삭제)
- ✅ GET /api/admin/roles/{comRoleId}/members (멤버 조회)
- ✅ PUT /api/admin/roles/{comRoleId}/members (멤버 업데이트)
- ✅ GET /api/admin/roles/{comRoleId}/permissions (권한 조회)
- ✅ PUT /api/admin/roles/{comRoleId}/permissions (권한 업데이트)

#### Resources 관리
- ✅ GET /api/admin/resources (목록 조회, keyword, type, parentId 필터)
- ✅ GET /api/admin/resources/tree (트리 조회)
- ✅ POST /api/admin/resources (생성)
- ✅ PUT /api/admin/resources/{comResourceId} (수정)
- ✅ DELETE /api/admin/resources/{comResourceId} (삭제)
- ⚠️ 부족: category, kind 필터 (보강 필요)

#### Code Management
- ✅ GET /api/admin/codes/groups (그룹 목록)
- ✅ POST /api/admin/codes/groups (그룹 생성)
- ✅ PUT /api/admin/codes/groups/{sysCodeGroupId} (그룹 수정)
- ✅ DELETE /api/admin/codes/groups/{sysCodeGroupId} (그룹 삭제)
- ✅ GET /api/admin/codes (코드 목록, groupKey 필터)
- ✅ POST /api/admin/codes (코드 생성)
- ✅ PUT /api/admin/codes/{sysCodeId} (코드 수정)
- ✅ DELETE /api/admin/codes/{sysCodeId} (코드 삭제)
- ✅ GET /api/admin/codes/usage (메뉴별 코드 조회)
- ✅ GET /api/admin/codes/usage/groups (메뉴별 코드 그룹 목록)

#### CodeUsage 관리
- ✅ GET /api/admin/code-usages (목록 조회, keyword, resourceKey 필터)
- ✅ POST /api/admin/code-usages (생성)
- ✅ PATCH /api/admin/code-usages/{sysCodeUsageId} (수정)
- ✅ DELETE /api/admin/code-usages/{sysCodeUsageId} (삭제)

---

### 2) RBAC Enforcement (서버 강제)

#### AdminGuardInterceptor
- ✅ 존재: `AdminGuardInterceptor.java`
- ✅ 경로: `/api/admin/**`, `/admin/**`
- ✅ 동작: JWT 인증 확인 → tenant_id 확인 → ADMIN role 검증
- ✅ 등록: `WebConfig`에 등록됨

#### AdminGuardService
- ✅ 존재: `AdminGuardService.java`
- ✅ 메서드:
  - `hasAdminRole(tenantId, userId)`: ADMIN 역할 보유 여부 확인
  - `requireAdminRole(tenantId, userId)`: ADMIN 역할 없으면 예외 발생
- ✅ CodeResolver 기반: `ROLE_CODE` 코드로 "ADMIN" 검증

#### 확장 포인트
- ⚠️ 현재: ADMIN role만 체크
- ✅ 향후 확장 가능: `canAccess(userId, tenantId, resourceKey, permissionCode)` 메서드 추가 가능

---

### 3) 엔티티 구조 확인

#### com_users / com_user_accounts
- ✅ 분리 구조 확정
- ✅ com_users: 사용자 프로필 정보
- ✅ com_user_accounts: 로그인 계정 정보 (LOCAL/SSO)

#### sys_codes
- ✅ tenant_id 적용 완료 (V17)
- ✅ 전사 공통 코드: tenant_id = NULL
- ✅ 테넌트별 커스텀 코드: tenant_id = 값

#### com_resources
- ✅ 확장 완료 (V16):
  - resource_category (MENU/UI_COMPONENT)
  - resource_kind (MENU_GROUP/PAGE/BUTTON 등)
  - event_key
  - event_actions (JSONB)
  - tracking_enabled
  - ui_scope

---

### 4) Validation/Policy

#### tenant_id 검증
- ✅ AdminGuardInterceptor에서 tenant_id 확인
- ✅ 모든 Repository 메서드에 tenant_id 필터 적용

#### CodeResolver 검증
- ✅ codeKey/groupKey 검증: CodeResolver 사용
- ✅ resourceKey 검증: com_resources 존재 여부 확인
- ✅ event_actions 검증: UI_ACTION 코드로만 구성

---

### 5) Audit Log

#### AuditLogService
- ✅ 존재: `AuditLogService.java`
- ✅ 기록 항목:
  - action (CREATE/UPDATE/DELETE 등)
  - resourceType (USER/ROLE/RESOURCE/CODE 등)
  - resourceId
  - before/after (JSON)
  - actorUserId
  - traceId

---

## ⚠️ 보강 필요 사항

### 1) ResourceController 필터링 보강
- 현재: keyword, type, parentId 필터만 지원
- 필요: category, kind 필터 추가

### 2) UserController 필터링 보강
- 현재: keyword, departmentId, roleId, status 필터 지원
- 필요: idpProviderType 필터 추가

### 3) 테스트 보강
- AdminGuardInterceptor 동작 검증 테스트 필요
- CRUD 최소 테스트 보강 필요

### 4) 문서 작성
- P1-5_ADMIN_CRUD_SPEC.md 신규 작성 필요
- README.md 업데이트 필요

---

## ✅ 이미 완료된 사항

- Admin CRUD API 대부분 구현 완료
- RBAC Enforcement (서버 강제) 구현 완료
- CodeUsage 관리 완료
- Audit Log 기록 완료
- tenant_id 기반 격리 완료

---

**결론**: 대부분의 기능이 이미 구현되어 있으며, 필터링 보강 및 테스트/문서 보완만 필요합니다.
