# Admin Users CRUD API 명세서

## 개요

운영 수준의 사용자 관리 API입니다. 사용자/계정/부서/상태/권한그룹 연결을 완전히 지원합니다.

**Base URL**: `/api/admin/users`

**인증**: JWT Bearer Token 필수  
**헤더**: `X-Tenant-ID` 필수

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

## 1) 사용자 목록 조회

### GET /api/admin/users

**Query Parameters**:
- `page` (default: 1): 페이지 번호
- `size` (default: 20): 페이지 크기
- `keyword` (optional): 이름/이메일/principal 통합 검색
- `departmentId` (optional): 부서 필터
- `roleId` (optional): 권한그룹(역할) 필터
- `status` (optional): 사용자 상태 (`USER_STATUS` 코드 기반)
- `idpProviderType` (optional): 인증 제공자 타입 (`IDP_PROVIDER_TYPE` 코드 기반)
- `loginType` (optional): 로그인 타입 (`LOCAL`, `SSO` 등, `LOGIN_TYPE` 코드 기반)

**Response**:
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "comUserId": 1,
        "tenantId": 1,
        "userName": "홍길동",
        "email": "hong@example.com",
        "departmentId": 1,
        "departmentName": "개발팀",
        "loginId": "hong",
        "status": "ACTIVE",
        "lastLoginAt": "2024-01-15T10:30:00",
        "createdAt": "2024-01-01T00:00:00",
        "updatedAt": "2024-01-15T10:30:00"
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 100,
    "totalPages": 5
  }
}
```

**성능 최적화**:
- `lastLoginAt`은 서브쿼리로 최신 1건만 조회 (join 폭발 방지)
- 역할은 summary 수준만 표시 (role count 또는 대표 role 1개)

**curl 예시**:
```bash
curl -X GET "http://localhost:8080/api/admin/users?page=1&size=20&keyword=홍&status=ACTIVE&loginType=LOCAL" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer {JWT}"
```

---

## 2) 사용자 상세 조회

### GET /api/admin/users/{comUserId}

**Path Parameters**:
- `comUserId`: 사용자 ID

**Response**:
```json
{
  "success": true,
  "data": {
    "comUserId": 1,
    "tenantId": 1,
    "userName": "홍길동",
    "email": "hong@example.com",
    "departmentId": 1,
    "status": "ACTIVE",
    "accounts": [
      {
        "comUserAccountId": 1,
        "providerType": "LOCAL",
        "principal": "hong",
        "enabled": true,
        "lastLoginAt": "2024-01-15T10:30:00"
      }
    ],
    "roles": [
      {
        "comRoleId": 1,
        "roleCode": "ADMIN",
        "roleName": "관리자",
        "subjectType": "USER",
        "isDepartmentBased": false,
        "assignedAt": "2024-01-01T00:00:00"
      },
      {
        "comRoleId": 2,
        "roleCode": "MEMBER",
        "roleName": "멤버",
        "subjectType": "DEPARTMENT",
        "isDepartmentBased": true,
        "assignedAt": null
      }
    ],
    "createdAt": "2024-01-01T00:00:00",
    "updatedAt": "2024-01-15T10:30:00"
  }
}
```

**특징**:
- 계정 목록: `LOCAL`, `SSO` 등 모든 계정 포함
- 역할 목록: 사용자 직접 할당 + 부서 기반 할당 모두 포함
- 부서 기반 역할은 `isDepartmentBased: true`로 표시, 수정 불가

**curl 예시**:
```bash
curl -X GET "http://localhost:8080/api/admin/users/1" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer {JWT}"
```

---

## 3) 사용자 생성

### POST /api/admin/users

**Request**:
```json
{
  "userName": "테스트 사용자",
  "email": "test@example.com",
  "departmentId": 1,
  "status": "ACTIVE",
  "accounts": [
    {
      "loginType": "LOCAL",
      "principal": "testuser",
      "password": "password123!"
    }
  ]
}
```

**Rules**:
- LOCAL 계정: `password` 필수, BCrypt로 해시 저장
- SSO 계정: `password` 없음, `providerKey` 필요할 수 있음 (정책 기반)
- 이메일 중복 체크 (테넌트 범위)
- 부서 존재 확인

**Response**: `UserDetail` (상세 조회와 동일)

**curl 예시**:
```bash
curl -X POST "http://localhost:8080/api/admin/users" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer {JWT}" \
  -H "Content-Type: application/json" \
  -d '{
    "userName": "테스트 사용자",
    "email": "test@example.com",
    "departmentId": 1,
    "status": "ACTIVE",
    "localAccount": {
      "principal": "testuser",
      "password": "password123!"
    }
  }'
```

---

## 4) 사용자 수정

### PATCH /api/admin/users/{comUserId}

**Request**:
```json
{
  "userName": "수정된 이름",
  "email": "updated@example.com",
  "departmentId": 2,
  "status": "LOCKED"
}
```

**Rules**:
- 모든 필드 optional (부분 수정 지원)
- 이메일 중복 체크 (본인 제외)
- 부서 존재 확인

**Response**: `UserDetail`

**curl 예시**:
```bash
curl -X PATCH "http://localhost:8080/api/admin/users/1" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer {JWT}" \
  -H "Content-Type: application/json" \
  -d '{
    "userName": "수정된 이름",
    "status": "LOCKED"
  }'
```

---

## 5) 사용자 삭제

### DELETE /api/admin/users/{comUserId}

**Rules**:
- 물리삭제 금지 (soft delete)
- `status`를 `INACTIVE`로 변경
- `role_members`, `accounts` 처리 정책 명확히 (비활성화)

**Response**:
```json
{
  "success": true,
  "data": null
}
```

**curl 예시**:
```bash
curl -X DELETE "http://localhost:8080/api/admin/users/1" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer {JWT}"
```

---

## 6) 역할 매핑 관리

### GET /api/admin/users/{comUserId}/roles

**Response**: `UserRoleInfo[]` (상세 조회의 `roles` 필드와 동일)

---

### PUT /api/admin/users/{comUserId}/roles

**Request**:
```json
{
  "roleIds": [1, 2, 3],
  "replace": true
}
```

**Rules**:
- `replace=true`: 기존 역할 모두 삭제 후 새로 추가
- `replace=false`: 기존 역할에 추가 (중복 제거)
- `subject_type=USER` 코드 기반 사용
- 부서 기반 역할은 별도로 표시되며 수정 불가

**Response**:
```json
{
  "success": true,
  "data": null
}
```

**curl 예시**:
```bash
curl -X PUT "http://localhost:8080/api/admin/users/1/roles" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer {JWT}" \
  -H "Content-Type: application/json" \
  -d '{
    "roleIds": [1, 2, 3],
    "replace": true
  }'
```

---

### POST /api/admin/users/{comUserId}/roles

**Request**:
```json
{
  "roleId": 2
}
```

**Rules**:
- 역할 추가 (중복 체크)
- 부서 기반 역할은 추가 불가

**Response**: `UserRoleInfo`

**curl 예시**:
```bash
curl -X POST "http://localhost:8080/api/admin/users/1/roles" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer {JWT}" \
  -H "Content-Type: application/json" \
  -d '{
    "roleId": 2
  }'
```

---

### DELETE /api/admin/users/{comUserId}/roles/{comRoleId}

**Rules**:
- 역할 삭제
- 부서 기반 역할은 삭제 불가

**Response**:
```json
{
  "success": true,
  "data": null
}
```

**curl 예시**:
```bash
curl -X DELETE "http://localhost:8080/api/admin/users/1/roles/2" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer {JWT}"
```

---

## 7) 코드 기반 필터 지원

### 필수 코드 그룹

`resourceKey = "menu.admin.users"`에 다음 코드 그룹이 매핑되어 있어야 합니다:

- `USER_STATUS`: 사용자 상태 (`ACTIVE`, `LOCKED`, `INVITED`, `DEPROVISIONED`)
- `IDP_PROVIDER_TYPE`: 인증 제공자 타입 (`LOCAL`, `OIDC`, `SAML`, `LDAP`)
- `SUBJECT_TYPE`: 주체 타입 (`USER`, `DEPARTMENT`)
- `LOGIN_TYPE`: 로그인 타입 (`LOCAL`, `SSO`) - 권장

**CodeUsage 확인**:
```bash
curl -X GET "http://localhost:8080/api/admin/codes/usage?resourceKey=menu.admin.users" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer {JWT}"
```

**Seed/Vx 확인**:
- `V13__seed_sys_code_usages.sql`에 이미 매핑되어 있음:
  - `SUBJECT_TYPE` ✅
  - `USER_STATUS` ✅
  - `IDP_PROVIDER_TYPE` ✅
- `LOGIN_TYPE`은 추가 권장 (현재는 `IDP_PROVIDER_TYPE`으로 대체 가능)

---

## 8) 보안

### 인증/인가

- `/api/admin/**`는 JWT 필수
- `AdminGuardInterceptor`가 자동으로 RBAC Enforcement 수행
- `menu.admin.users` + `VIEW`/`EDIT`/`EXECUTE` 권한 검사

### 멀티테넌시

- 모든 조회/수정/삭제는 `tenantId` 필터 강제
- 타 테넌트 데이터 접근 불가

---

## 9) 감사 로그

모든 변경 작업은 `com_audit_logs`에 기록됩니다:

- `USER_CREATE`: 사용자 생성
- `USER_UPDATE`: 사용자 수정
- `USER_STATUS_UPDATE`: 상태 변경
- `USER_DELETE`: 사용자 삭제
- `USER_ROLE_UPDATE`: 역할 업데이트
- `USER_ROLE_ADD`: 역할 추가
- `USER_ROLE_REMOVE`: 역할 삭제

---

## 10) 에러 코드

| 에러 코드 | HTTP 상태 | 설명 |
|---------|---------|------|
| E2000 | 401 | 인증 필요 |
| E2001 | 403 | 권한 없음 |
| E3000 | 400 | 잘못된 요청 |
| E3001 | 404 | 엔티티 없음 |
| E3002 | 409 | 중복 엔티티 |
| E3003 | 400 | 유효하지 않은 코드값 |

---

## 11) 변경 파일 리스트

### 수정된 파일

1. `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/UserSummary.java`
   - `lastLoginAt` 필드 추가

2. `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/UserRoleInfo.java`
   - `subjectType`, `isDepartmentBased` 필드 추가

3. `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/UpdateUserRolesRequest.java`
   - `replace` 필드 추가

4. `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/UserManagementService.java`
   - `getUsers()`: `loginType` 파라미터 추가
   - `toUserSummary()`: `lastLoginAt` 조회 로직 추가
   - `getUserRoles()`: 부서 기반 역할 포함 로직 추가
   - `updateUserRoles()`: `replace` 로직 추가
   - `addUserRole()`: 역할 추가 메서드 추가
   - `removeUserRole()`: 역할 삭제 메서드 추가

5. `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/UserController.java`
   - `getUsers()`: `loginType` 파라미터 추가
   - `addUserRole()`: 역할 추가 엔드포인트 추가
   - `removeUserRole()`: 역할 삭제 엔드포인트 추가

### 새로 생성된 파일

1. `dwp-auth-server/src/test/java/com/dwp/services/auth/controller/admin/UserControllerTest.java`
   - 사용자 목록 조회 테스트
   - 사용자 생성 테스트 (LOCAL 계정 + BCrypt 검증)
   - 역할 업데이트 테스트 (replace=true)
   - 역할 추가/삭제 테스트

2. `docs/api-spec/USER_ADMIN_CRUD_API.md`
   - API 명세서

---

## 12) 완료 기준

- ✅ API 기능 및 응답 형식 유지
- ✅ `lastLoginAt` 필드 추가 및 조회 로직 구현
- ✅ `loginType` 필터 추가
- ✅ 역할 추가/삭제 API 구현
- ✅ 부서 기반 역할 표시 구현
- ✅ 테스트 작성 (최소 3개)
- ✅ 문서화 완료

---

## 13) 향후 개선 사항

1. **SSO 계정 생성**: 현재는 LOCAL 계정만 지원, SSO 계정 생성 로직 추가 필요
2. **부서 변경 시 역할 처리**: 부서 변경 시 부서 기반 역할 자동 반영 여부 결정 필요
3. **일괄 작업**: 여러 사용자에 대한 일괄 역할 할당/해제 API 추가 고려
4. **검색 성능**: 대용량 데이터에서 keyword 검색 성능 최적화 필요
