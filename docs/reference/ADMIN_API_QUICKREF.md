# Admin API Quick Reference

**작성일**: 2026-01-20  
**버전**: v1.0.0

---

## 공통 사항

### Base URL
```
/api/admin
```

### 필수 헤더
```
Authorization: Bearer <JWT>
X-Tenant-ID: <tenant_id>
```

### 공통 Query 파라미터 (리스트 API)
- `page` (기본값: 1, 1-based)
- `size` (기본값: 20)
- `keyword` (통합 검색, optional)
- `enabled` (true/false, optional)

### 표준 응답 형식
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

## 1. Users 관리

### 1.1 사용자 목록 조회
**GET** `/api/admin/users`

**Query Parameters**:
- `page`, `size`, `keyword`
- `departmentId` (부서 필터)
- `roleId` (역할 필터)
- `status` (USER_STATUS 코드)
- `idpProviderType` (IDP_PROVIDER_TYPE 코드)

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
        "principal": "hong",
        "status": "ACTIVE",
        "lastLoginAt": "2026-01-20T10:00:00"
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 100,
    "totalPages": 5
  }
}
```

**curl 예시**:
```bash
curl -X GET "http://localhost:8080/api/admin/users?page=1&size=20&keyword=홍" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-Tenant-ID: 1"
```

### 1.2 사용자 생성
**POST** `/api/admin/users`

**Request Body**:
```json
{
  "displayName": "홍길동",
  "email": "hong@example.com",
  "principal": "hong",
  "password": "password123!",
  "primaryDepartmentId": 1,
  "status": "ACTIVE"
}
```

**권한**: `menu.admin.users` EDIT

### 1.3 사용자 수정
**PATCH** `/api/admin/users/{comUserId}`

**권한**: `menu.admin.users` EDIT

### 1.4 사용자 삭제
**DELETE** `/api/admin/users/{comUserId}`

**권한**: `menu.admin.users` EDIT

---

## 2. Roles 관리

### 2.1 역할 목록 조회
**GET** `/api/admin/roles`

**Query Parameters**:
- `page`, `size`, `keyword`

**권한**: `menu.admin.roles` VIEW

### 2.2 역할 생성
**POST** `/api/admin/roles`

**Request Body**:
```json
{
  "roleName": "관리자",
  "roleCode": "ADMIN",
  "description": "시스템 관리자"
}
```

**권한**: `menu.admin.roles` EDIT

### 2.3 역할 수정
**PATCH** `/api/admin/roles/{comRoleId}`

**권한**: `menu.admin.roles` EDIT

### 2.4 역할 삭제
**DELETE** `/api/admin/roles/{comRoleId}`

**권한**: `menu.admin.roles` EDIT

### 2.5 역할 권한 관리
**GET** `/api/admin/roles/{comRoleId}/permissions`  
**PUT** `/api/admin/roles/{comRoleId}/permissions`

**Request Body** (권한 업데이트):
```json
{
  "permissions": [
    {
      "resourceKey": "menu.admin.users",
      "permissionCode": "VIEW",
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

**권한**: `menu.admin.roles` EDIT

---

## 3. Resources 관리

### 3.1 리소스 목록 조회
**GET** `/api/admin/resources`

**Query Parameters**:
- `page`, `size`, `keyword`
- `type` (MENU/UI_COMPONENT)
- `category` (MENU/UI_COMPONENT)
- `kind` (MENU_GROUP/PAGE/BUTTON 등)
- `parentId`
- `enabled` (true/false)
- `trackingEnabled` (true/false)

**Response**:
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "resourceId": 1,
        "resourceKey": "menu.admin.users",
        "name": "사용자 관리",
        "type": "MENU",
        "resourceCategory": "MENU",
        "resourceKind": "PAGE",
        "trackingEnabled": true,
        "enabled": true
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 50,
    "totalPages": 3
  }
}
```

**curl 예시**:
```bash
curl -X GET "http://localhost:8080/api/admin/resources?page=1&size=20&type=MENU" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-Tenant-ID: 1"
```

### 3.2 리소스 트리 조회
**GET** `/api/admin/resources/tree`

**Response**:
```json
{
  "success": true,
  "data": [
    {
      "resourceId": 1,
      "resourceKey": "menu.admin",
      "name": "관리",
      "children": [
        {
          "resourceId": 2,
          "resourceKey": "menu.admin.users",
          "name": "사용자 관리"
        }
      ]
    }
  ]
}
```

### 3.3 리소스 생성
**POST** `/api/admin/resources`

**Request Body**:
```json
{
  "resourceKey": "menu.admin.users",
  "resourceName": "사용자 관리",
  "resourceCategory": "MENU",
  "resourceKind": "PAGE",
  "parentResourceId": 1,
  "eventActions": ["VIEW", "USE"],
  "trackingEnabled": true,
  "enabled": true
}
```

**권한**: `menu.admin.resources` EDIT

### 3.4 리소스 수정
**PATCH** `/api/admin/resources/{comResourceId}`

**권한**: `menu.admin.resources` EDIT

### 3.5 리소스 삭제
**DELETE** `/api/admin/resources/{comResourceId}`

**주의**: 하위 리소스가 있으면 409 Conflict

**권한**: `menu.admin.resources` EDIT

---

## 4. Menus 관리

### 4.1 메뉴 목록 조회
**GET** `/api/admin/menus`

**Query Parameters**:
- `page`, `size`, `keyword`
- `enabled` (true/false)
- `parentId`

**권한**: `menu.admin.menus` VIEW

### 4.2 메뉴 트리 조회
**GET** `/api/admin/menus/tree`

**Response**:
```json
{
  "success": true,
  "data": [
    {
      "menuKey": "menu.admin",
      "menuName": "관리",
      "children": [
        {
          "menuKey": "menu.admin.users",
          "menuName": "사용자 관리"
        }
      ]
    }
  ]
}
```

**권한**: `menu.admin.menus` VIEW

### 4.3 메뉴 생성
**POST** `/api/admin/menus`

**Request Body**:
```json
{
  "menuKey": "menu.admin.users",
  "menuName": "사용자 관리",
  "routePath": "/admin/users",
  "icon": "user",
  "parentMenuId": 1,
  "sortOrder": 10,
  "enabled": true,
  "visible": true
}
```

**주의**: Menu 생성 시 `com_resources`에 자동으로 MENU 리소스가 생성됩니다 (`menuKey = resourceKey`)

**권한**: `menu.admin.menus` EDIT

### 4.4 메뉴 수정
**PATCH** `/api/admin/menus/{sysMenuId}`

**권한**: `menu.admin.menus` EDIT

### 4.5 메뉴 삭제
**DELETE** `/api/admin/menus/{sysMenuId}`

**주의**: 하위 메뉴가 있으면 409 Conflict

**권한**: `menu.admin.menus` EDIT

### 4.6 메뉴 정렬/이동
**PUT** `/api/admin/menus/reorder`

**Request Body**:
```json
{
  "items": [
    {
      "menuId": 1,
      "parentId": null,
      "sortOrder": 10
    },
    {
      "menuId": 2,
      "parentId": null,
      "sortOrder": 20
    }
  ]
}
```

**권한**: `menu.admin.menus` EDIT

---

## 5. Code Groups 관리

### 5.1 코드 그룹 목록 조회
**GET** `/api/admin/codes/groups`

**Response**:
```json
{
  "success": true,
  "data": [
    {
      "sysCodeGroupId": 1,
      "groupKey": "RESOURCE_TYPE",
      "groupName": "리소스 타입",
      "description": "리소스 타입 코드"
    }
  ]
}
```

**권한**: `menu.admin.codes` VIEW

### 5.2 코드 그룹 생성
**POST** `/api/admin/codes/groups`

**Request Body**:
```json
{
  "groupKey": "RESOURCE_TYPE",
  "groupName": "리소스 타입",
  "description": "리소스 타입 코드"
}
```

**권한**: `menu.admin.codes` EDIT

### 5.3 코드 그룹 수정
**PUT** `/api/admin/codes/groups/{sysCodeGroupId}`

**권한**: `menu.admin.codes` EDIT

### 5.4 코드 그룹 삭제
**DELETE** `/api/admin/codes/groups/{sysCodeGroupId}`

**주의**: codes가 있으면 409 Conflict

**권한**: `menu.admin.codes` EDIT

---

## 6. Codes 관리

### 6.1 코드 목록 조회
**GET** `/api/admin/codes`

**Query Parameters**:
- `groupKey` (필수, 그룹 키)
- `tenantScope` (COMMON | TENANT | ALL, 기본값: ALL)
- `enabled` (true/false)

**Response**:
```json
{
  "success": true,
  "data": [
    {
      "sysCodeId": 1,
      "groupKey": "RESOURCE_TYPE",
      "code": "MENU",
      "name": "메뉴",
      "sortOrder": 10,
      "enabled": true,
      "tenantId": null
    }
  ]
}
```

**curl 예시**:
```bash
curl -X GET "http://localhost:8080/api/admin/codes?groupKey=RESOURCE_TYPE&tenantScope=ALL" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-Tenant-ID: 1"
```

**권한**: `menu.admin.codes` VIEW

### 6.2 코드 생성
**POST** `/api/admin/codes`

**Request Body**:
```json
{
  "groupKey": "RESOURCE_TYPE",
  "codeKey": "MENU",
  "codeName": "메뉴",
  "sortOrder": 10,
  "enabled": true,
  "tenantId": null
}
```

**주의**: `tenantId`가 null이면 공통 코드, 값이 있으면 tenant 전용 코드

**권한**: `menu.admin.codes` EDIT

### 6.3 코드 수정
**PUT** `/api/admin/codes/{sysCodeId}`

**권한**: `menu.admin.codes` EDIT

### 6.4 코드 삭제
**DELETE** `/api/admin/codes/{sysCodeId}`

**권한**: `menu.admin.codes` EDIT

---

## 7. Code Usage 관리

### 7.1 코드 사용 정의 목록 조회
**GET** `/api/admin/code-usages`

**Query Parameters**:
- `page`, `size`
- `resourceKey` (리소스 키 필터)
- `keyword` (resourceKey/groupKey 검색)
- `enabled` (true/false)

**Response**:
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "sysCodeUsageId": 1,
        "tenantId": 1,
        "resourceKey": "menu.admin.users",
        "codeGroupKey": "USER_STATUS",
        "scope": "MENU",
        "enabled": true,
        "sortOrder": 10
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 50,
    "totalPages": 3
  }
}
```

**권한**: `menu.admin.code-usages` VIEW

### 7.2 코드 사용 정의 생성
**POST** `/api/admin/code-usages`

**Request Body**:
```json
{
  "resourceKey": "menu.admin.users",
  "codeGroupKey": "USER_STATUS",
  "scope": "MENU",
  "enabled": true,
  "sortOrder": 10
}
```

**권한**: `menu.admin.code-usages` EDIT

### 7.3 코드 사용 정의 수정
**PATCH** `/api/admin/code-usages/{sysCodeUsageId}`

**권한**: `menu.admin.code-usages` EDIT

### 7.4 코드 사용 정의 삭제
**DELETE** `/api/admin/code-usages/{sysCodeUsageId}`

**권한**: `menu.admin.code-usages` EDIT

### 7.5 메뉴별 코드 조회
**GET** `/api/admin/codes/usage?resourceKey=menu.admin.users`

**Response**:
```json
{
  "success": true,
  "data": {
    "USER_STATUS": [
      {
        "code": "ACTIVE",
        "name": "활성"
      },
      {
        "code": "LOCKED",
        "name": "잠금"
      }
    ]
  }
}
```

**보안**: ADMIN 권한 + resourceKey 접근 권한 (VIEW) 필수

**권한**: `menu.admin.codes` VIEW + `resourceKey` VIEW

---

## 8. Audit Logs 관리

### 8.1 감사 로그 목록 조회
**GET** `/api/admin/audit-logs`

**Query Parameters**:
- `page`, `size`
- `from` (ISO 8601, 예: 2026-01-01T00:00:00)
- `to` (ISO 8601, 예: 2026-01-31T23:59:59)
- `actorUserId` (행위자 사용자 ID)
- `actionType` (USER_CREATE, ROLE_UPDATE 등)
- `resourceKey` (리소스 키)
- `keyword` (통합 검색)

**Response**:
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "auditLogId": 1,
        "tenantId": 1,
        "actorUserId": 10,
        "action": "USER_CREATE",
        "resourceType": "USER",
        "resourceId": 100,
        "metadata": {
          "before": null,
          "after": {
            "comUserId": 100,
            "displayName": "홍길동"
          },
          "ipAddress": "192.168.1.1"
        },
        "truncated": false,
        "createdAt": "2026-01-20T10:00:00"
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 1000,
    "totalPages": 50
  }
}
```

**curl 예시**:
```bash
curl -X GET "http://localhost:8080/api/admin/audit-logs?page=1&size=20&actionType=USER_CREATE" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-Tenant-ID: 1"
```

**권한**: `menu.admin.audit-logs` VIEW

### 8.2 감사 로그 Excel 다운로드
**POST** `/api/admin/audit-logs/export`

**Request Body**:
```json
{
  "from": "2026-01-01T00:00:00",
  "to": "2026-01-31T23:59:59",
  "actionType": "USER_CREATE",
  "maxRows": 1000
}
```

**Response**: Excel 파일 (application/octet-stream)

**주의**: 대량 데이터는 성능 이슈가 있을 수 있습니다. 향후 비동기 taskId 방식으로 개선 예정입니다.

**권한**: `menu.admin.audit-logs` VIEW

---

## 권한 매핑 정책

### VIEW 권한
- LIST/READ 조회 API
- 예: `menu.admin.users` VIEW → Users 목록/상세 조회 가능

### EDIT 권한
- CREATE/UPDATE/DELETE 작업 API
- 예: `menu.admin.users` EDIT → Users 생성/수정/삭제 가능

### 권한 체크
- 모든 Admin CRUD API는 `PermissionEvaluator.requirePermission()`으로 권한 체크
- 권한이 없으면 403 Forbidden 반환

---

## 에러 코드

### 409 Conflict
- `DUPLICATE_ENTITY`: 중복 키 (menuKey, resourceKey 등)
- `RESOURCE_HAS_CHILDREN`: 하위 리소스/메뉴 존재

### 403 Forbidden
- `FORBIDDEN`: 권한 없음

### 404 Not Found
- `ENTITY_NOT_FOUND`: 엔티티 없음

---

## 참고 문서

- [RELEASE_NOTES_PR04_PR10.md](./RELEASE_NOTES_PR04_PR10.md): 배포 노트
- [SECURITY_RBAC_ENFORCEMENT.md](./SECURITY_RBAC_ENFORCEMENT.md): RBAC 정책
- [CODE_TENANT_POLICY.md](./CODE_TENANT_POLICY.md): 코드 정책

---

**작성일**: 2026-01-20  
**작성자**: DWP Backend Team
