# BE P1-5 Final - SubPrompt 2/4: 사전 점검 결과

**작성일**: 2026-01-20  
**목적**: Admin CRUD 운영 수준 API 완성 전 기존 API 확인

---

## ✅ 기존 API 존재 여부 확인

### 1) Users Admin API
- ✅ **GET** `/api/admin/users` - 목록 조회 (keyword, departmentId, roleId, status, idpProviderType 필터)
- ✅ **POST** `/api/admin/users` - 생성
- ✅ **GET** `/api/admin/users/{comUserId}` - 상세 조회
- ✅ **PUT** `/api/admin/users/{comUserId}` - 수정
- ✅ **POST** `/api/admin/users/{comUserId}/status` - 상태 변경
- ✅ **DELETE** `/api/admin/users/{comUserId}` - 삭제
- ✅ **POST** `/api/admin/users/{comUserId}/reset-password` - 비밀번호 재설정
- ✅ **GET** `/api/admin/users/{comUserId}/roles` - 역할 조회
- ✅ **PUT** `/api/admin/users/{comUserId}/roles` - 역할 업데이트

**상태**: ✅ **기존 존재** - 운영 수준으로 보강 필요

---

### 2) Roles Admin API
- ✅ **GET** `/api/admin/roles` - 목록 조회 (keyword 필터)
- ✅ **POST** `/api/admin/roles` - 생성
- ✅ **GET** `/api/admin/roles/{comRoleId}` - 상세 조회
- ✅ **PUT** `/api/admin/roles/{comRoleId}` - 수정
- ✅ **DELETE** `/api/admin/roles/{comRoleId}` - 삭제

**상태**: ✅ **기존 존재** - 운영 수준으로 보강 필요

---

### 3) Role Members 관리 API
- ✅ **GET** `/api/admin/roles/{comRoleId}/members` - 멤버 조회
- ✅ **PUT** `/api/admin/roles/{comRoleId}/members` - 멤버 업데이트 (bulk)

**부족 사항**:
- ⚠️ **POST** `/api/admin/roles/{comRoleId}/members` - 개별 추가 (신규 필요)
- ⚠️ **DELETE** `/api/admin/roles/{comRoleId}/members/{comRoleMemberId}` - 개별 삭제 (신규 필요)

**상태**: ⚠️ **기존 존재** - 개별 추가/삭제 API 보강 필요

---

### 4) Role Permissions Bulk API
- ✅ **GET** `/api/admin/roles/{comRoleId}/permissions` - 권한 조회
- ✅ **PUT** `/api/admin/roles/{comRoleId}/permissions` - 권한 업데이트 (bulk)

**현재 구현**:
- `UpdateRolePermissionsRequest`는 `resourceId`, `permissionId` 기반
- 요구사항: `resourceKey`, `permissionCode` 기반으로 변경 필요
- `effect=null`이면 삭제 로직 필요

**상태**: ⚠️ **기존 존재** - resourceKey/permissionCode 기반으로 보강 필요

---

### 5) Resources CRUD API
- ✅ **GET** `/api/admin/resources` - 목록 조회 (keyword, type, category, kind, parentId, enabled 필터)
- ✅ **GET** `/api/admin/resources/tree` - 트리 조회
- ✅ **POST** `/api/admin/resources` - 생성
- ✅ **PUT** `/api/admin/resources/{comResourceId}` - 수정
- ✅ **DELETE** `/api/admin/resources/{comResourceId}` - 삭제

**상태**: ✅ **기존 존재** - 운영 수준으로 보강 필요

---

### 6) Menu Tree / Resource Tree API
- ✅ **GET** `/api/admin/resources/tree` - 리소스 트리 조회
- ✅ **GET** `/api/auth/menus/tree` - 메뉴 트리 조회 (권한 기반)

**상태**: ✅ **기존 존재** - 확인 필요

---

### 7) Codes + CodeUsage CRUD API
- ✅ **GET** `/api/admin/codes/groups` - 그룹 목록
- ✅ **POST** `/api/admin/codes/groups` - 그룹 생성
- ✅ **PUT** `/api/admin/codes/groups/{sysCodeGroupId}` - 그룹 수정
- ✅ **DELETE** `/api/admin/codes/groups/{sysCodeGroupId}` - 그룹 삭제
- ✅ **GET** `/api/admin/codes` - 코드 목록 (groupKey 필터)
- ✅ **POST** `/api/admin/codes` - 코드 생성
- ✅ **PUT** `/api/admin/codes/{sysCodeId}` - 코드 수정
- ✅ **DELETE** `/api/admin/codes/{sysCodeId}` - 코드 삭제
- ✅ **GET** `/api/admin/codes/usage?resourceKey=...` - 메뉴별 코드 조회
- ✅ **GET** `/api/admin/code-usages` - CodeUsage 목록
- ✅ **POST** `/api/admin/code-usages` - CodeUsage 생성
- ✅ **PATCH** `/api/admin/code-usages/{sysCodeUsageId}` - CodeUsage 수정
- ✅ **DELETE** `/api/admin/code-usages/{sysCodeUsageId}` - CodeUsage 삭제

**상태**: ✅ **기존 존재** - 운영 수준으로 보강 필요

---

## 🔍 보강 필요 사항

### 1) Role Members 개별 추가/삭제 API
- **POST** `/api/admin/roles/{comRoleId}/members` - 개별 추가
- **DELETE** `/api/admin/roles/{comRoleId}/members/{comRoleMemberId}` - 개별 삭제

### 2) Role Permissions Bulk API 개선
- `resourceKey`, `permissionCode` 기반으로 변경
- `effect=null`이면 삭제 로직 추가
- CodeResolver 기반 검증 강화

### 3) Users API 응답 구조 개선
- `loginId/principal` 필드 추가 확인
- `departmentName` 필드 추가 확인

### 4) Audit Log 확인
- 모든 CRUD 작업에 audit log 기록 확인
- action 타입 표준화 확인

### 5) 테스트 보강
- Users CRUD 테스트
- RolePermissions bulk upsert + delete 테스트
- RoleMembers 추가/삭제 테스트
- Resources tree 테스트

---

## 📋 작업 계획

### 기존 API 보강
1. ✅ Users API - 응답 구조 확인 및 보강
2. ✅ Roles API - 운영 수준 확인
3. ⚠️ Role Members API - 개별 추가/삭제 API 추가
4. ⚠️ Role Permissions Bulk API - resourceKey/permissionCode 기반으로 변경
5. ✅ Resources API - 운영 수준 확인
6. ✅ Menu Tree API - 확인
7. ✅ Codes + CodeUsage API - 운영 수준 확인

### 신규 API 추가
- Role Members 개별 추가/삭제 API

### 테스트 작성
- Users CRUD 테스트
- RolePermissions bulk upsert + delete 테스트
- RoleMembers 추가/삭제 테스트
- Resources tree 테스트

### 문서화
- `ADMIN_CRUD_API_SPEC.md` 작성/업데이트

---

**결론**: 대부분의 API가 이미 존재하나, Role Members 개별 추가/삭제 API와 Role Permissions Bulk API 개선이 필요합니다.
