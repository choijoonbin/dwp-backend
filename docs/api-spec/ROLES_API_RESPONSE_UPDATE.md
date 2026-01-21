# Roles API 응답 구조 업데이트

## 작성일
2026-01-21

## 목적
프론트엔드 `RoleSummary` 타입과 백엔드 `/api/admin/roles` 응답 구조를 일치시키기 위한 DTO 수정

---

## 변경 사항

### 1. RoleSummary DTO 필드 추가/변경

#### 추가된 필드
- `id: String` - 역할 ID를 문자열로 변환 (필수)
- `status: String` - 역할 상태 'ACTIVE' | 'INACTIVE' (필수)
- `memberCount: Integer` - 역할 멤버 수 (선택, 권장)

#### 유지된 필드 (호환성)
- `comRoleId: Long` - 기존 필드 유지 (과도기 1~2주)

#### 변경 전
```java
public class RoleSummary {
    private Long comRoleId;
    private String roleCode;
    private String roleName;
    private String description;
    private LocalDateTime createdAt;
}
```

#### 변경 후
```java
public class RoleSummary {
    private String id;              // 추가: 문자열 ID
    private Long comRoleId;          // 유지: 호환성
    private String roleCode;
    private String roleName;
    private String status;           // 추가: 'ACTIVE' | 'INACTIVE'
    private String description;
    private LocalDateTime createdAt;
    private Integer memberCount;    // 추가: 멤버 수
}
```

### 2. RoleDetail DTO 필드 추가/변경

RoleSummary와 동일한 필드 추가/변경 적용

---

## 적용된 엔드포인트

다음 엔드포인트들이 자동으로 새로운 응답 구조를 반환합니다:

1. **GET /api/admin/roles** (목록 조회)
   - `RoleQueryService.getRoles()` 사용
   - 각 역할에 대해 `memberCount` 계산

2. **GET /api/admin/roles/{id}** (상세 조회)
   - `RoleQueryService.getRoleDetail()` 사용
   - `memberCount` 계산

3. **POST /api/admin/roles** (생성)
   - `RoleCommandService.createRole()` → `RoleQueryService.getRoleDetail()` 사용
   - 생성된 역할의 `memberCount` 포함

4. **PUT /api/admin/roles/{id}** (수정)
   - `RoleCommandService.updateRole()` → `RoleQueryService.getRoleDetail()` 사용
   - 수정된 역할의 `memberCount` 포함

---

## 응답 예시

### GET /api/admin/roles 응답

```json
{
    "status": "SUCCESS",
    "message": "요청이 성공적으로 처리되었습니다.",
    "data": {
        "items": [
            {
                "id": "1",                    // 추가: 문자열 ID
                "comRoleId": 1,              // 유지: 호환성
                "roleCode": "ADMIN",
                "roleName": "Administrator",
                "status": "ACTIVE",          // 추가: 상태
                "description": "Full system access",
                "createdAt": "2026-01-21T11:41:27.559529",
                "memberCount": 5             // 추가: 멤버 수
            }
        ],
        "page": 1,
        "size": 200,
        "totalItems": 1,
        "totalPages": 1
    }
}
```

### GET /api/admin/roles/{id} 응답

```json
{
    "status": "SUCCESS",
    "message": "요청이 성공적으로 처리되었습니다.",
    "data": {
        "id": "1",
        "comRoleId": 1,
        "roleCode": "ADMIN",
        "roleName": "Administrator",
        "status": "ACTIVE",
        "description": "Full system access",
        "createdAt": "2026-01-21T11:41:27.559529",
        "updatedAt": "2026-01-21T12:00:00.000000",
        "memberCount": 5
    }
}
```

---

## 구현 세부사항

### memberCount 계산

`RoleQueryService`에서 `RoleMemberRepository.countByTenantIdAndRoleId()`를 사용하여 멤버 수를 계산합니다.

```java
long memberCount = roleMemberRepository.countByTenantIdAndRoleId(tenantId, roleId);
```

### status 기본값

현재 `Role` 엔티티에 `status` 필드가 없으므로, 기본값으로 `"ACTIVE"`를 설정합니다.

**참고**: 향후 `Role` 엔티티에 `status` 필드를 추가하면, 엔티티의 실제 값을 사용하도록 변경할 수 있습니다.

### id 필드 변환

`roleId` (Long)를 `String.valueOf()`로 문자열로 변환하여 `id` 필드에 설정합니다.

---

## 호환성 유지

- `comRoleId` 필드는 계속 유지되어 기존 코드와의 호환성을 보장합니다.
- 프론트엔드는 `id` 필드를 우선 사용하고, 필요시 `comRoleId`를 fallback으로 사용할 수 있습니다.
- 과도기(1~2주) 후 `comRoleId` 제거 검토 가능

---

## 테스트

- `RoleControllerTest` 업데이트 완료
- 모든 엔드포인트가 새로운 응답 구조를 반환하는지 확인 필요

---

## 참고 문서

- 프론트엔드 타입 정의: `libs/shared-utils/src/admin/types.ts`
- 프론트엔드 요청 문서: `/Users/joonbinchoi/Work/dwp/dwp-frontend/docs/BACKEND_API_ROLES_RESPONSE_FIX.md`
