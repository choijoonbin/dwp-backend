# PR-02: Users CRUD 운영 수준 API 완성

## 목표
Admin > Users 화면이 운영 수준 CRUD가 되도록 API를 완성합니다.

## 작업 완료 내역

### PR-02A: Users 조회 API (List) ✅
- **API**: `GET /api/admin/users`
- **변경 사항**:
  - 정렬을 `created_at DESC`로 변경 (기존: `display_name ASC`)
  - tenant_id 필터 강제 (이미 구현됨)
  - 페이징: 1-base (기본값: page=1, size=20)
- **Query 파라미터**:
  - `page` (1-base)
  - `size`
  - `keyword` (name/principal/email)
  - `status` (USER_STATUS 코드 기반)
  - `departmentId` (optional)
- **Response**: `ApiResponse<Page<UserSummary>>` (totalElements/totalPages 포함)

### PR-02B: Users 생성 API (Create) ✅
- **API**: `POST /api/admin/users`
- **변경 사항**:
  - 중복 검증(409) 추가: email/principal 중복 시 `DUPLICATE_ENTITY` (CONFLICT) 반환
  - `UserValidator.validateCreateRequest()`에서 중복 검증 수행
- **Request 필드**:
  - `userName` (필수)
  - `email` (필수)
  - `departmentId` (optional)
  - `status` (USER_STATUS 코드 기반, 기본값: ACTIVE)
  - `localAccount`: `{ providerType=LOCAL, principal, password }`
- **규칙**:
  - principal/email 중복이면 409 CONFLICT
  - status는 CodeResolver(USER_STATUS)로 검증
  - password는 BCrypt로 해시

### PR-02C: Users 수정 API (Update) ✅
- **API**: `PATCH /api/admin/users/{comUserId}`
- **변경 사항**:
  - principal 수정 금지 (운영 위험)
  - userName/email/status/department 변경 가능
- **제한 사항**:
  - principal(계정 username) 수정은 별도 API로 분리 필요 (향후 구현)
  - UpdateUserRequest에 principal 필드가 없으므로 자동으로 금지됨

### PR-02D: Users 삭제 API (Soft delete) ✅
- **API**: `DELETE /api/admin/users/{comUserId}`
- **변경 사항**:
  - Soft delete 구현 (status = INACTIVE)
  - Idempotent 처리: 이미 삭제된 경우(INACTIVE) 200 OK 반환
- **동작**:
  - 첫 삭제: status를 INACTIVE로 변경 + Audit Log 기록
  - 재삭제: 이미 INACTIVE면 그대로 반환 (idempotent)

### PR-02E: Audit Log 기록 ✅
- **이미 구현됨**: 모든 CRUD 작업에 Audit Log 기록
- **기록 내용**:
  - `actor` (userId)
  - `action` (USER_CREATE, USER_UPDATE, USER_DELETE 등)
  - `before/after` JSON
  - IP 주소, User-Agent
- **서비스**: `AuditLogService.recordAuditLog()` 사용

## 변경 파일 리스트

### Repository 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/UserRepository.java`
  - 정렬을 `ORDER BY u.created_at DESC`로 변경

### Service 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/user/UserCommandService.java`
  - `createUser()`: 중복 검증 주석 추가
  - `updateUser()`: principal 수정 금지 주석 추가
  - `deleteUser()`: idempotent 처리 추가

### 테스트 추가
- `dwp-auth-server/src/test/java/com/dwp/services/auth/controller/admin/UserControllerTest.java`
  - PR-02: 사용자 생성 성공 테스트
  - PR-02: 중복 principal → 409 테스트
  - PR-02: tenant isolation 테스트

## API 응답 예시

### 1. 사용자 생성 성공 (200)
```bash
POST /api/admin/users
Headers:
  X-Tenant-ID: 1
  Authorization: Bearer <admin_token>
Body:
{
  "userName": "새 사용자",
  "email": "newuser@example.com",
  "departmentId": 1,
  "status": "ACTIVE",
  "localAccount": {
    "principal": "newuser",
    "password": "password123!"
  }
}
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "comUserId": 100,
    "tenantId": 1,
    "userName": "새 사용자",
    "email": "newuser@example.com",
    "status": "ACTIVE"
  }
}
```

### 2. 중복 principal → 409
```bash
POST /api/admin/users
Headers:
  X-Tenant-ID: 1
Body:
{
  "userName": "중복 사용자",
  "email": "duplicate@example.com",
  "localAccount": {
    "principal": "duplicateuser",  // 이미 존재
    "password": "password123!"
  }
}
```

**응답** (409 Conflict):
```json
{
  "success": false,
  "errorCode": "E3001",
  "message": "이미 존재하는 principal입니다."
}
```

### 3. 사용자 목록 조회 (200)
```bash
GET /api/admin/users?page=1&size=20&keyword=홍&status=ACTIVE
Headers:
  X-Tenant-ID: 1
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "comUserId": 1,
        "userName": "홍길동",
        "email": "hong@example.com",
        "status": "ACTIVE",
        "lastLoginAt": "2026-01-20T10:30:00"
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 1,
    "totalPages": 1
  }
}
```

### 4. 사용자 삭제 (200, idempotent)
```bash
DELETE /api/admin/users/100
Headers:
  X-Tenant-ID: 1
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": null
}
```

**재삭제 시도** (200 OK, idempotent):
- 이미 INACTIVE 상태면 그대로 200 OK 반환

## 보안 및 검증

### 1. Tenant 격리
- 모든 조회/수정/삭제는 `tenant_id` 필터 강제
- 다른 tenantId로 조회 시도 시 404 반환

### 2. 중복 검증 (409)
- **Email 중복**: `UserValidator.validateEmailNotDuplicate()`
- **Principal 중복**: `UserValidator.validatePrincipalNotDuplicate()`
- 중복 시 `DUPLICATE_ENTITY` (E3001, CONFLICT) 반환

### 3. 코드 기반 검증
- `status`: CodeResolver(USER_STATUS)로 검증
- `providerType`: CodeResolver(IDP_PROVIDER_TYPE)로 검증

### 4. Audit Log
- 모든 CRUD 작업 기록
- before/after JSON 포함
- IP 주소, User-Agent 포함

## 다음 단계
- principal 수정 API 별도 구현 (향후)
- 통합 테스트 보강 (실제 DB 사용)
- 성능 최적화 (인덱스 확인)
