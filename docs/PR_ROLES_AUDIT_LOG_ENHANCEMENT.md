# Roles 감사로그 강화 완료 요약

## 작성일
2026-01-21

## 목적
Role/RoleMember/RolePermission 변경 이력을 완전 추적 가능하도록 감사로그 강화

---

## 완료 사항

### 1. RoleAuditHelper 유틸리티 클래스 생성 ✅
- `RoleAuditHelper.java` 생성
- Bulk 권한 변경 시 diff 추적 메서드 제공
- 변경된 항목만 식별 가능하도록 `changedOnly` 배열 생성

**주요 메서드:**
- `createPermissionDiff()`: 권한 변경 diff 생성 (ADDED/UPDATED/REMOVED)
- `simplifyPermissions()`: 권한 목록 간소화 (핵심 필드만)

### 2. RolePermissionCommandService 감사로그 강화 ✅

#### 변경 전
```java
// 감사 로그
auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_PERMISSION_BULK_UPDATE", "ROLE", roleId,
        null, request, httpRequest);
```

#### 변경 후
```java
// 감사 로그 (diff 추적 강화)
Map<String, Object> auditMetadata = new HashMap<>();

// 변경 전/후 간소화된 권한 목록
auditMetadata.put("before", roleAuditHelper.simplifyPermissions(beforePermissions, resourceMap, permissionMap));
auditMetadata.put("after", roleAuditHelper.simplifyPermissions(afterPermissions, resourceMap, permissionMap));

// 변경된 항목만 식별 가능하도록 diff 생성
List<Map<String, Object>> changedOnly = roleAuditHelper.createPermissionDiff(
        beforePermissions, afterPermissions, resourceMap, permissionMap);
auditMetadata.put("changedOnly", changedOnly);
auditMetadata.put("changeCount", changedOnly.size());

auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_PERMISSION_BULK_UPDATE", 
        "ROLE_PERMISSION", roleId, auditMetadata, httpRequest);
```

**감사로그 메타데이터 구조:**
```json
{
  "before": [
    {"resourceKey": "menu.admin.users", "permissionCode": "VIEW", "effect": "ALLOW"},
    {"resourceKey": "menu.admin.users", "permissionCode": "EDIT", "effect": "DENY"}
  ],
  "after": [
    {"resourceKey": "menu.admin.users", "permissionCode": "VIEW", "effect": "ALLOW"},
    {"resourceKey": "menu.admin.users", "permissionCode": "EDIT", "effect": "ALLOW"}
  ],
  "changedOnly": [
    {
      "resourceKey": "menu.admin.users",
      "permissionCode": "EDIT",
      "beforeEffect": "DENY",
      "afterEffect": "ALLOW",
      "changeType": "UPDATED"
    }
  ],
  "changeCount": 1
}
```

### 3. RoleMemberCommandService 감사로그 강화 ✅

#### updateRoleMembers (Bulk 업데이트)
- 변경 전/후 멤버 목록 기록
- `changedOnly` 배열로 추가/삭제된 멤버만 식별

#### addRoleMember (개별 추가)
- `subjectType`, `subjectId`, `subjectName`, `subjectEmail` 포함

#### removeRoleMember (개별 삭제)
- `subjectType`, `subjectId`, `subjectName`, `subjectEmail` 포함

**감사로그 메타데이터 구조 (Bulk 업데이트):**
```json
{
  "before": [
    {"subjectType": "USER", "subjectId": 1},
    {"subjectType": "DEPARTMENT", "subjectId": 2}
  ],
  "after": [
    {"subjectType": "USER", "subjectId": 1},
    {"subjectType": "USER", "subjectId": 3}
  ],
  "changedOnly": [
    {
      "subjectType": "DEPARTMENT",
      "subjectId": 2,
      "changeType": "REMOVED"
    },
    {
      "subjectType": "USER",
      "subjectId": 3,
      "changeType": "ADDED"
    }
  ],
  "changeCount": 2
}
```

### 4. RoleCommandService 개선 ✅
- `copyRole()` 메서드에 `status` 필드 추가

---

## 감사로그 이벤트 타입

### 역할 관련
- `ROLE_CREATE`: 역할 생성
- `ROLE_UPDATE`: 역할 수정 (before/after 포함)
- `ROLE_DELETE`: 역할 삭제 (before 포함)

### 역할 멤버 관련
- `ROLE_MEMBER_UPDATE`: Bulk 멤버 업데이트 (before/after/changedOnly 포함)
- `ROLE_MEMBER_ADD`: 개별 멤버 추가 (subjectType/subjectId/subjectName/subjectEmail 포함)
- `ROLE_MEMBER_REMOVE`: 개별 멤버 삭제 (subjectType/subjectId/subjectName/subjectEmail 포함)

### 역할 권한 관련
- `ROLE_PERMISSION_BULK_UPDATE`: Bulk 권한 업데이트 (before/after/changedOnly 포함)

---

## 감사로그 메타데이터 필드

### 공통 필드
- `ipAddress`: 클라이언트 IP 주소
- `userAgent`: User-Agent 헤더

### RolePermission Bulk 업데이트
- `before`: 변경 전 권한 목록 (간소화)
- `after`: 변경 후 권한 목록 (간소화)
- `changedOnly`: 변경된 항목만 (resourceKey, permissionCode, beforeEffect, afterEffect, changeType)
- `changeCount`: 변경된 항목 수

### RoleMember Bulk 업데이트
- `before`: 변경 전 멤버 목록 (간소화)
- `after`: 변경 후 멤버 목록 (간소화)
- `changedOnly`: 변경된 항목만 (subjectType, subjectId, changeType)
- `changeCount`: 변경된 항목 수

### RoleMember 개별 추가/삭제
- `subjectType`: USER 또는 DEPARTMENT
- `subjectId`: 사용자 ID 또는 부서 ID
- `subjectName`: 사용자명 또는 부서명
- `subjectEmail`: 사용자 이메일 (USER인 경우)

---

## 변경된 파일 목록

### 신규 파일
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/roles/RoleAuditHelper.java`

### 수정된 파일
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/roles/RolePermissionCommandService.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/roles/RoleMemberCommandService.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/roles/RoleCommandService.java`

---

## 효과

### 1. 변경 추적성 향상
- Bulk 권한 변경 시 어떤 항목이 어떻게 변경되었는지 명확히 식별 가능
- DENY → ALLOW 같은 효과 변경 추적 가능

### 2. 감사로그 크기 최적화
- 변경 전/후 전체 목록 + 변경된 항목만 별도 제공
- 변경된 항목만 확인하면 되므로 조회 효율성 향상

### 3. 운영 편의성 향상
- 변경 실패 케이스 원인 파악 용이
- 변경 이력 추적 및 롤백 검토 가능

---

## 참고

- 기존 `AuditLogService`의 truncate 정책 (10KB) 유지
- `changedOnly` 배열은 변경된 항목만 포함하므로 크기 최소화
- `before`/`after`는 간소화된 형태로 저장 (핵심 필드만)
