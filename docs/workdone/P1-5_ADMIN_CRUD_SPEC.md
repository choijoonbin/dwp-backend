# BE P1-5 (Enhanced): Admin CRUD API 명세서

**작성일**: 2026-01-20  
**목적**: 운영 가능한 Admin CRUD API + RBAC Enforcement(서버 강제) + CodeUsage 관리 완성

---

## 핵심 정책 (상단 5줄)

1. **RBAC Enforcement (서버 강제)**: `/api/admin/**` 모든 요청은 `AdminGuardInterceptor`에서 ADMIN 역할을 강제 검증 (403 Forbidden)
2. **tenant_id 격리**: 모든 데이터는 tenant_id 기준으로 강제 격리, FK 제약 없음
3. **CodeUsage 기반 코드 조회**: 프론트엔드는 `/api/admin/codes/usage?resourceKey=...`로 메뉴별 필요한 코드만 조회
4. **표준 응답**: 모든 API 응답은 `ApiResponse<T>` 또는 `ApiResponse<PageResponse<T>>` 형식
5. **Audit Log**: 모든 Admin CRUD 작업은 `com_audit_logs`에 기록 (action, entity, entityId, before/after, actorUserId)

---

## 1. 공통 사항

### 1.1 API Prefix
- 모든 Admin CRUD API는 `/api/admin/**` 하위에서 제공

### 1.2 필수 헤더
```
Authorization: Bearer <JWT>
X-Tenant-ID: <tenant_id>
X-User-ID: <user_id> (가능하면)
X-DWP-Source: FRONTEND
X-DWP-Caller-Type: USER
```

### 1.3 공통 Query 파라미터 (리스트 API)
- `page` (1-based, 기본값: 1)
- `size` (기본값: 20)
- `sort` (예: `createdAt,desc`)
- `keyword` (통합 검색)
- `enabled` (true/false) 또는 `status`
- `from`/`to` (필요시, ISO 8601)

### 1.4 표준 응답 형식
```json
{
  "success": true,
  "data": {
    "items": [...],
    "page": 1,
    "size": 20,
    "totalItems": 100,
    "totalPages": 5
  }
}
```

---

## 2. Users 관리

### 2.1 사용자 목록 조회
**GET** `/api/admin/users`

**Query Parameters**:
- `page` (기본값: 1)
- `size` (기본값: 20)
- `keyword` (이름/이메일/사번/principal 검색)
- `departmentId` (부서 필터)
- `roleId` (역할 필터)
- `status` (ACTIVE/LOCKED 등)
- `idpProviderType` (LOCAL/SSO 등) ⭐ 보강

**Response**:
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "comUserId": 1,
        "displayName": "홍길동",
        "email": "hong@example.com",
        "employeeNumber": "E001",
        "status": "ACTIVE",
        "primaryDepartmentId": 10,
        "primaryDepartmentName": "개발팀",
        "createdAt": "2026-01-20T10:00:00"
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 100,
    "totalPages": 5
  }
}
```

### 2.2 사용자 생성
**POST** `/api/admin/users`

**Request**:
```json
{
  "displayName": "홍길동",
  "email": "hong@example.com",
  "employeeNumber": "E001",
  "primaryDepartmentId": 10,
  "createAccount": true,
  "accountPrincipal": "hong",
  "accountPassword": "password123",
  "idpProviderType": "LOCAL"
}
```

### 2.3 사용자 수정
**PUT** `/api/admin/users/{comUserId}`

### 2.4 사용자 상태 변경
**POST** `/api/admin/users/{comUserId}/status`

**Request**:
```json
{
  "status": "LOCKED",
  "reason": "비정상 접근 시도"
}
```

### 2.5 비밀번호 재설정
**POST** `/api/admin/users/{comUserId}/reset-password`

**Request**:
```json
{
  "newPassword": "newPassword123",
  "temporary": true
}
```

### 2.6 사용자 역할 관리
- **GET** `/api/admin/users/{comUserId}/roles`: 역할 조회
- **PUT** `/api/admin/users/{comUserId}/roles`: 역할 업데이트

---

## 3. Roles 관리

### 3.1 역할 목록 조회
**GET** `/api/admin/roles`

**Query Parameters**:
- `page`, `size`, `keyword`

### 3.2 역할 생성
**POST** `/api/admin/roles`

**Request**:
```json
{
  "roleCode": "MANAGER",
  "roleName": "매니저",
  "description": "매니저 역할"
}
```

### 3.3 역할 멤버 관리
- **GET** `/api/admin/roles/{comRoleId}/members`: 멤버 조회
- **PUT** `/api/admin/roles/{comRoleId}/members`: 멤버 업데이트

**Request** (멤버 업데이트):
```json
{
  "userIds": [1, 2, 3],
  "departmentIds": [10, 20]
}
```

### 3.4 역할 권한 관리 ⭐ 핵심
- **GET** `/api/admin/roles/{comRoleId}/permissions`: 권한 조회
- **PUT** `/api/admin/roles/{comRoleId}/permissions`: 권한 업데이트 (bulk)

**Request** (권한 업데이트):
```json
{
  "permissions": [
    {
      "resourceKey": "menu.admin.users",
      "permissionCode": "USE",
      "effect": "ALLOW"
    },
    {
      "resourceKey": "menu.admin.users",
      "permissionCode": "EDIT",
      "effect": "DENY"
    }
  ]
}
```

**Response** (권한 조회):
```json
{
  "success": true,
  "data": [
    {
      "comRoleId": 1,
      "comResourceId": 10,
      "resourceKey": "menu.admin.users",
      "resourceName": "사용자 관리",
      "comPermissionId": 1,
      "permissionCode": "USE",
      "permissionName": "사용",
      "effect": "ALLOW"
    }
  ]
}
```

---

## 4. Resources 관리

### 4.1 리소스 목록 조회
**GET** `/api/admin/resources`

**Query Parameters**:
- `page`, `size`, `keyword`
- `type` (MENU/UI_COMPONENT)
- `category` (MENU/UI_COMPONENT) ⭐ 보강
- `kind` (MENU_GROUP/PAGE/BUTTON 등) ⭐ 보강
- `parentId`
- `enabled` ⭐ 보강

### 4.2 리소스 트리 조회
**GET** `/api/admin/resources/tree`

### 4.3 리소스 생성
**POST** `/api/admin/resources`

**Request**:
```json
{
  "resourceKey": "menu.admin.users",
  "resourceName": "사용자 관리",
  "resourceType": "MENU",
  "resourceCategory": "MENU",
  "resourceKind": "PAGE",
  "parentResourceKey": "menu.admin",
  "eventKey": "menu.admin.users:view",
  "eventActions": ["VIEW", "USE"],
  "trackingEnabled": true,
  "enabled": true
}
```

---

## 5. Code Management

### 5.1 코드 그룹 CRUD
- **GET** `/api/admin/codes/groups`: 그룹 목록
- **POST** `/api/admin/codes/groups`: 그룹 생성
- **PUT** `/api/admin/codes/groups/{sysCodeGroupId}`: 그룹 수정
- **DELETE** `/api/admin/codes/groups/{sysCodeGroupId}`: 그룹 삭제

### 5.2 코드 CRUD
- **GET** `/api/admin/codes?groupKey=...`: 그룹별 코드 목록
- **POST** `/api/admin/codes`: 코드 생성
- **PUT** `/api/admin/codes/{sysCodeId}`: 코드 수정
- **DELETE** `/api/admin/codes/{sysCodeId}`: 코드 삭제

### 5.3 메뉴별 코드 조회 ⭐ 핵심
**GET** `/api/admin/codes/usage?resourceKey=menu.admin.users`

**Response**:
```json
{
  "success": true,
  "data": {
    "codes": {
      "UI_ACTION": [
        { "code": "VIEW", "name": "조회", ... },
        { "code": "CLICK", "name": "클릭", ... }
      ],
      "SUBJECT_TYPE": [
        { "code": "USER", "name": "사용자", ... }
      ]
    }
  }
}
```

---

## 6. CodeUsage 관리

### 6.1 코드 사용 정의 목록 조회
**GET** `/api/admin/code-usages`

**Query Parameters**:
- `page`, `size`
- `resourceKey`
- `keyword`

### 6.2 코드 사용 정의 생성
**POST** `/api/admin/code-usages`

**Request**:
```json
{
  "resourceKey": "menu.admin.users",
  "codeGroupKey": "UI_ACTION",
  "scope": "MENU",
  "enabled": true,
  "sortOrder": 10,
  "remark": "Events 탭 필터용"
}
```

### 6.3 코드 사용 정의 수정
**PATCH** `/api/admin/code-usages/{sysCodeUsageId}`

### 6.4 코드 사용 정의 삭제
**DELETE** `/api/admin/code-usages/{sysCodeUsageId}`

---

## 7. RBAC Enforcement (서버 강제)

### 7.1 동작 방식
- `AdminGuardInterceptor`가 `/api/admin/**` 경로를 가로챔
- JWT 인증 확인 → tenant_id 확인 → ADMIN 역할 검증
- ADMIN 역할 없으면 `403 Forbidden` 반환

### 7.2 확장 포인트
- 현재: ADMIN role만 체크
- 향후: `canAccess(userId, tenantId, resourceKey, permissionCode)` 메서드 추가 가능

---

## 8. 에러 코드

| 에러 코드 | HTTP 상태 | 설명 |
|---------|---------|------|
| E2000 | 401 | 인증 필요 |
| E2001 | 403 | 권한 없음 (ADMIN 역할 필요) |
| E3000 | 400 | 잘못된 요청 |
| E3001 | 404 | 엔티티 없음 |
| E3002 | 409 | 중복 엔티티 |

---

## 9. curl 예시

### 사용자 목록 조회
```bash
curl -X GET "http://localhost:8080/api/admin/users?page=1&size=20&keyword=홍&idpProviderType=LOCAL" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-Tenant-ID: 1"
```

### 역할 권한 업데이트
```bash
curl -X PUT "http://localhost:8080/api/admin/roles/1/permissions" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-Tenant-ID: 1" \
  -H "Content-Type: application/json" \
  -d '{
    "permissions": [
      {
        "resourceKey": "menu.admin.users",
        "permissionCode": "USE",
        "effect": "ALLOW"
      }
    ]
  }'
```

---

**작성자**: DWP Backend Team  
**최종 업데이트**: 2026-01-20
