# [DWP Backend] BE Admin Users CRUD (운영급) 완료 보고서

## 작업 개요

Admin > 사용자 관리(menu.admin.users) 화면을 운영 수준 CRUD로 완성했습니다.

**작업 기간**: 2024-01-XX  
**작업자**: AI Assistant  
**상태**: ✅ 완료

---

## 0) 기존 코드 재사용 확인

### ✅ 재사용된 컴포넌트

- **Entity**: `com_users`, `com_user_accounts`, `com_departments`, `com_roles`, `com_role_members`
- **Repository**: `UserRepository`, `UserAccountRepository`, `DepartmentRepository`, `RoleRepository`, `RoleMemberRepository`, `LoginHistoryRepository`
- **Service**: `UserManagementService` (기존 서비스 확장)
- **Controller**: `UserController` (기존 컨트롤러 확장)
- **Util**: `CodeResolver`, `CodeUsageService` (코드 하드코딩 방지)
- **Security**: `AdminGuardInterceptor` (RBAC Enforcement)

### 📝 보완 사항

1. `UserSummary`에 `lastLoginAt` 필드 추가
2. `loginType` 필터 추가 (기존 `idpProviderType`과 병행 지원)
3. `UpdateUserRolesRequest`에 `replace` 필드 추가
4. 역할 추가/삭제 API 추가 (`POST`, `DELETE`)
5. `UserRoleInfo`에 부서 기반 역할 표시 필드 추가

---

## 1) 구현된 API 목록

### 조회 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/admin/users` | 사용자 목록 조회 (페이징, 검색, 필터) |
| GET | `/api/admin/users/{comUserId}` | 사용자 상세 조회 |
| GET | `/api/admin/users/{comUserId}/roles` | 사용자 역할 조회 |

### 생성/수정/삭제 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/admin/users` | 사용자 생성 |
| PATCH | `/api/admin/users/{comUserId}` | 사용자 수정 |
| DELETE | `/api/admin/users/{comUserId}` | 사용자 삭제 (soft delete) |

### 역할 관리 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| PUT | `/api/admin/users/{comUserId}/roles` | 역할 업데이트 (replace/append) |
| POST | `/api/admin/users/{comUserId}/roles` | 역할 추가 |
| DELETE | `/api/admin/users/{comUserId}/roles/{comRoleId}` | 역할 삭제 |

---

## 2) 주요 기능

### 2.1 사용자 목록 조회

- **페이징**: `page`, `size` 파라미터 지원
- **검색**: `keyword`로 이름/이메일/principal 통합 검색
- **필터링**:
  - `departmentId`: 부서 필터
  - `roleId`: 권한그룹 필터
  - `status`: 사용자 상태 (`USER_STATUS` 코드 기반)
  - `idpProviderType`: 인증 제공자 타입 (`IDP_PROVIDER_TYPE` 코드 기반)
  - `loginType`: 로그인 타입 (`LOGIN_TYPE` 코드 기반, 신규 추가)
- **성능 최적화**: `lastLoginAt`은 서브쿼리로 최신 1건만 조회 (join 폭발 방지)

### 2.2 사용자 생성

- **LOCAL 계정**: `password` 필수, BCrypt로 해시 저장
- **SSO 계정**: `password` 없음, `providerKey` 필요할 수 있음 (정책 기반)
- **검증**: 이메일 중복 체크 (테넌트 범위), 부서 존재 확인

### 2.3 사용자 수정

- **부분 수정**: 모든 필드 optional (PATCH 메서드)
- **검증**: 이메일 중복 체크 (본인 제외), 부서 존재 확인

### 2.4 사용자 삭제

- **Soft Delete**: 물리삭제 금지, `status`를 `INACTIVE`로 변경
- **연관 데이터**: `role_members`, `accounts` 처리 정책 명확히 (비활성화)

### 2.5 역할 관리

- **역할 조회**: 사용자 직접 할당 + 부서 기반 할당 모두 포함
- **역할 업데이트**: `replace=true`면 기존 역할 모두 삭제 후 새로 추가, `replace=false`면 추가만
- **역할 추가/삭제**: 개별 역할 추가/삭제 지원
- **부서 기반 역할**: 별도로 표시되며 수정 불가 (정책상)

---

## 3) 변경 파일 리스트

### 수정된 파일

1. **`dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/UserSummary.java`**
   - `lastLoginAt` 필드 추가

2. **`dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/UserRoleInfo.java`**
   - `subjectType`, `isDepartmentBased` 필드 추가

3. **`dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/UpdateUserRolesRequest.java`**
   - `replace` 필드 추가

4. **`dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/UserManagementService.java`**
   - `getUsers()`: `loginType` 파라미터 추가
   - `toUserSummary()`: `lastLoginAt` 조회 로직 추가
   - `getUserRoles()`: 부서 기반 역할 포함 로직 추가
   - `updateUserRoles()`: `replace` 로직 추가
   - `addUserRole()`: 역할 추가 메서드 추가
   - `removeUserRole()`: 역할 삭제 메서드 추가

5. **`dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/UserController.java`**
   - `getUsers()`: `loginType` 파라미터 추가
   - `addUserRole()`: 역할 추가 엔드포인트 추가
   - `removeUserRole()`: 역할 삭제 엔드포인트 추가

### 새로 생성된 파일

1. **`dwp-auth-server/src/test/java/com/dwp/services/auth/controller/admin/UserControllerTest.java`**
   - 사용자 목록 조회 테스트
   - 사용자 생성 테스트 (LOCAL 계정 + BCrypt 검증)
   - 역할 업데이트 테스트 (replace=true)
   - 역할 추가/삭제 테스트

2. **`docs/api-spec/USER_ADMIN_CRUD_API.md`**
   - API 명세서

---

## 4) 코드 기반 필터 지원

### 필수 코드 그룹

`resourceKey = "menu.admin.users"`에 다음 코드 그룹이 매핑되어 있습니다:

- ✅ `USER_STATUS`: 사용자 상태 (`ACTIVE`, `LOCKED`, `INVITED`, `DEPROVISIONED`)
- ✅ `IDP_PROVIDER_TYPE`: 인증 제공자 타입 (`LOCAL`, `OIDC`, `SAML`, `LDAP`)
- ✅ `SUBJECT_TYPE`: 주체 타입 (`USER`, `DEPARTMENT`)
- ⚠️ `LOGIN_TYPE`: 로그인 타입 (`LOCAL`, `SSO`) - 현재는 `IDP_PROVIDER_TYPE`으로 대체 가능

**CodeUsage 확인**:
- `V13__seed_sys_code_usages.sql`에 이미 매핑되어 있음
- 추가 Seed/Vx 불필요

---

## 5) 보안

### 인증/인가

- `/api/admin/**`는 JWT 필수
- `AdminGuardInterceptor`가 자동으로 RBAC Enforcement 수행
- `menu.admin.users` + `VIEW`/`EDIT`/`EXECUTE` 권한 검사

### 멀티테넌시

- 모든 조회/수정/삭제는 `tenantId` 필터 강제
- 타 테넌트 데이터 접근 불가

---

## 6) 감사 로그

모든 변경 작업은 `com_audit_logs`에 기록됩니다:

- `USER_CREATE`: 사용자 생성
- `USER_UPDATE`: 사용자 수정
- `USER_STATUS_UPDATE`: 상태 변경
- `USER_DELETE`: 사용자 삭제
- `USER_ROLE_UPDATE`: 역할 업데이트
- `USER_ROLE_ADD`: 역할 추가
- `USER_ROLE_REMOVE`: 역할 삭제

---

## 7) 테스트

### 작성된 테스트

1. **사용자 목록 조회 테스트**
   - 페이징 + keyword 검색
   - `lastLoginAt` 필드 포함 확인

2. **사용자 생성 테스트**
   - LOCAL 계정 + BCrypt 검증
   - 이메일 중복 체크

3. **역할 업데이트 테스트**
   - `replace=true` 동작 확인

4. **역할 추가/삭제 테스트**
   - 개별 역할 추가/삭제 동작 확인

### 테스트 실행

```bash
./gradlew :dwp-auth-server:test --tests UserControllerTest
```

---

## 8) 완료 기준 체크리스트

- ✅ API 기능 및 응답 형식 유지
- ✅ `lastLoginAt` 필드 추가 및 조회 로직 구현
- ✅ `loginType` 필터 추가
- ✅ 역할 추가/삭제 API 구현
- ✅ 부서 기반 역할 표시 구현
- ✅ 테스트 작성 (최소 3개)
- ✅ 문서화 완료

---

## 9) 향후 개선 사항

1. **SSO 계정 생성**: 현재는 LOCAL 계정만 지원, SSO 계정 생성 로직 추가 필요
2. **부서 변경 시 역할 처리**: 부서 변경 시 부서 기반 역할 자동 반영 여부 결정 필요
3. **일괄 작업**: 여러 사용자에 대한 일괄 역할 할당/해제 API 추가 고려
4. **검색 성능**: 대용량 데이터에서 keyword 검색 성능 최적화 필요

---

## 10) 참고 문서

- [API 명세서](./api-spec/USER_ADMIN_CRUD_API.md)
- [Code Management Guide](./guides/CODE_MANAGEMENT.md)
- [RBAC Calculation Policy](./guides/RBAC_CALCULATION_POLICY.md)

---

## 11) 요약

운영 수준의 사용자 관리 API를 완성했습니다. 기존 코드를 최대한 재사용하면서 필요한 기능을 보완했습니다. 모든 API는 멀티테넌시를 지원하며, 코드 하드코딩을 방지하고 감사 로그를 기록합니다.

**주요 성과**:
- ✅ 사용자 목록 조회 (페이징, 검색, 필터)
- ✅ 사용자 생성/수정/삭제
- ✅ 역할 관리 (조회, 추가, 삭제, 업데이트)
- ✅ 부서 기반 역할 표시
- ✅ 테스트 작성 및 문서화 완료
