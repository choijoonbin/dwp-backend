# Code Tenant Policy: 테넌트별 코드 정책

**작성일**: 2026-01-20  
**버전**: v1.0.0

---

## 개요

DWP Backend는 시스템 공통 코드와 테넌트 커스텀 코드를 분리하여 관리합니다. 이 정책은 코드 시스템의 장기 운영 안정성을 보장합니다.

---

## 테넌트별 코드 정책

### 1. 시스템 공통 코드 (`tenant_id = NULL`)

**정의**: 모든 테넌트에서 공통으로 사용하는 코드

**특징**:
- `tenant_id = NULL`로 저장
- 모든 테넌트에서 조회 가능
- 수정/삭제는 운영자만 가능 (향후 권한 강화)

**예시**:
- `RESOURCE_TYPE`: MENU, UI_COMPONENT 등
- `PERMISSION_CODE`: VIEW, EDIT, DELETE 등
- `EFFECT_TYPE`: ALLOW, DENY
- `IDP_PROVIDER_TYPE`: LOCAL, OIDC, SAML

**사용 예시**:
```sql
INSERT INTO sys_codes (group_key, code, name, tenant_id, ...)
VALUES ('RESOURCE_TYPE', 'MENU', '메뉴', NULL, ...);
```

---

### 2. 테넌트 커스텀 코드 (`tenant_id = {tenant}`)

**정의**: 특정 테넌트에서만 사용하는 커스터마이징 코드

**특징**:
- `tenant_id = {tenant}`로 저장
- 해당 테넌트에서만 조회 가능
- 해당 테넌트의 ADMIN만 수정/삭제 가능

**예시**:
- `USER_STATUS`: 테넌트별 사용자 상태 코드 (ACTIVE, LOCKED, SUSPENDED 등)
- `ROLE_CODE`: 테넌트별 역할 코드 (MANAGER, STAFF 등)
- `DEPARTMENT_TYPE`: 테넌트별 부서 타입 코드

**사용 예시**:
```sql
INSERT INTO sys_codes (group_key, code, name, tenant_id, ...)
VALUES ('USER_STATUS', 'SUSPENDED', '일시정지', 1, ...);
```

---

## 코드 조회 우선순위

### 우선순위 정책

**현재 구현**: **Tenant 우선 → Common Fallback**

1. **테넌트 커스텀 코드** (`tenant_id = {tenant}`) 우선 조회
2. **시스템 공통 코드** (`tenant_id = NULL`) Fallback

**예시**:
```
Tenant 1에서 USER_STATUS 코드 조회:
1. tenant_id = 1인 USER_STATUS 코드 조회 (우선)
2. 없으면 tenant_id = NULL인 USER_STATUS 코드 조회 (Fallback)
```

### CodeUsage 기반 조회

**메뉴별 코드 조회** (`GET /api/admin/codes/usage?resourceKey=...`):
- `sys_code_usages`에 등록된 `groupKey`만 반환
- 각 `groupKey`별로 tenant 우선 → common fallback 정책 적용

**예시**:
```json
{
  "USER_STATUS": [
    {"code": "SUSPENDED", "name": "일시정지"},  // tenant 전용 코드
    {"code": "ACTIVE", "name": "활성"},        // common 코드 (fallback)
    {"code": "LOCKED", "name": "잠금"}         // common 코드 (fallback)
  ]
}
```

---

## 코드 생성 정책

### 공통 코드 생성

**API**: `POST /api/admin/codes`

**Request Body**:
```json
{
  "groupKey": "RESOURCE_TYPE",
  "codeKey": "MENU",
  "codeName": "메뉴",
  "tenantId": null  // null이면 공통 코드
}
```

**권한**: 운영자만 가능 (향후 권한 강화)

### 테넌트 코드 생성

**API**: `POST /api/admin/codes`

**Request Body**:
```json
{
  "groupKey": "USER_STATUS",
  "codeKey": "SUSPENDED",
  "codeName": "일시정지",
  "tenantId": 1  // 값이 있으면 테넌트 전용 코드
}
```

**권한**: 해당 테넌트의 ADMIN만 가능

---

## 코드 조회 API

### 1. 그룹별 코드 목록 조회

**API**: `GET /api/admin/codes?groupKey=USER_STATUS&tenantScope=ALL`

**Query Parameters**:
- `groupKey` (필수): 코드 그룹 키
- `tenantScope` (선택): COMMON | TENANT | ALL (기본값: ALL)
  - `COMMON`: 공통 코드만 조회
  - `TENANT`: 테넌트 코드만 조회
  - `ALL`: 공통 + 테넌트 코드 모두 조회
- `enabled` (선택): true/false

**Response**:
```json
{
  "success": true,
  "data": [
    {
      "sysCodeId": 1,
      "groupKey": "USER_STATUS",
      "code": "ACTIVE",
      "name": "활성",
      "tenantId": null  // 공통 코드
    },
    {
      "sysCodeId": 2,
      "groupKey": "USER_STATUS",
      "code": "SUSPENDED",
      "name": "일시정지",
      "tenantId": 1  // 테넌트 전용 코드
    }
  ]
}
```

### 2. 메뉴별 코드 조회

**API**: `GET /api/admin/codes/usage?resourceKey=menu.admin.users`

**보안**:
- ADMIN 권한 필수
- resourceKey 접근 권한 (VIEW) 체크
- enabled된 code group만 반환

**Response**:
```json
{
  "success": true,
  "data": {
    "USER_STATUS": [
      {"code": "SUSPENDED", "name": "일시정지"},  // tenant 우선
      {"code": "ACTIVE", "name": "활성"},        // common fallback
      {"code": "LOCKED", "name": "잠금"}         // common fallback
    ]
  }
}
```

---

## CodeUsage의 보안 목적

### CodeUsage란?

**정의**: 특정 리소스(메뉴)에서 사용할 코드 그룹을 정의하는 매핑 테이블

**목적**:
1. **보안**: 메뉴별로 필요한 코드만 노출
2. **성능**: 불필요한 코드 조회 방지
3. **커스터마이징**: 메뉴별 코드 그룹 커스터마이징

**예시**:
```sql
-- menu.admin.users 메뉴에서 USER_STATUS 코드 그룹 사용
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, enabled)
VALUES (1, 'menu.admin.users', 'USER_STATUS', true);
```

### CodeUsage 없으면?

- **코드 조회 불가**: `GET /api/admin/codes/usage?resourceKey=...`에서 빈 맵 반환
- **보안**: 등록되지 않은 코드 그룹은 노출되지 않음

---

## 중복 방지 정책

### 코드 중복 규칙

**유니크 제약**: `groupKey` + `code` + `tenantId`

**예시**:
```sql
-- ✅ 가능: 공통 코드와 테넌트 코드는 별도로 존재 가능
INSERT INTO sys_codes (group_key, code, name, tenant_id) VALUES ('USER_STATUS', 'ACTIVE', '활성', NULL);
INSERT INTO sys_codes (group_key, code, name, tenant_id) VALUES ('USER_STATUS', 'ACTIVE', '활성', 1);

-- ❌ 불가: 동일 tenant_id에서 중복
INSERT INTO sys_codes (group_key, code, name, tenant_id) VALUES ('USER_STATUS', 'ACTIVE', '활성', 1);
INSERT INTO sys_codes (group_key, code, name, tenant_id) VALUES ('USER_STATUS', 'ACTIVE', '활성', 1);
-- → 409 DUPLICATE_ENTITY
```

---

## 캐시 무효화 정책

### Code 변경 시

**캐시 무효화**: `CodeResolver.clearCache(groupKey)`

**예시**:
```java
// 코드 생성/수정/삭제 시
codeResolver.clearCache("USER_STATUS");
log.info("Code cache cleared for groupKey: USER_STATUS");
```

### CodeUsage 변경 시

**캐시 무효화**: `CodeUsageService.clearCache(tenantId, resourceKey)`

**예시**:
```java
// CodeUsage 생성/수정/삭제 시
codeUsageService.clearCache(tenantId, "menu.admin.users");
log.info("Code usage cache cleared: tenantId={}, resourceKey={}", tenantId, resourceKey);
```

---

## 운영 주의사항

### 1. 공통 코드 수정 시 영향도

**주의**: 공통 코드 수정 시 모든 테넌트에 영향

**예시**:
```sql
-- 공통 코드 수정
UPDATE sys_codes SET name = '메뉴 (변경)' WHERE group_key = 'RESOURCE_TYPE' AND code = 'MENU' AND tenant_id IS NULL;
-- → 모든 테넌트에서 "메뉴 (변경)"으로 표시됨
```

**권장**: 공통 코드는 신중하게 수정, 테넌트별 커스터마이징은 테넌트 코드로 분리

### 2. 테넌트 코드 삭제 시 영향도

**주의**: 테넌트 코드 삭제 시 해당 테넌트에서만 영향

**예시**:
```sql
-- 테넌트 코드 삭제
DELETE FROM sys_codes WHERE group_key = 'USER_STATUS' AND code = 'SUSPENDED' AND tenant_id = 1;
-- → Tenant 1에서만 "SUSPENDED" 코드가 사라짐, 다른 테넌트는 영향 없음
```

### 3. CodeUsage 등록 필수

**주의**: 메뉴에서 코드를 사용하려면 CodeUsage 등록 필수

**예시**:
```sql
-- CodeUsage 등록 없이 코드 조회 시도
GET /api/admin/codes/usage?resourceKey=menu.admin.users
-- → 빈 맵 반환 {}
```

---

## 테스트 가이드

### 공통 코드 vs 테넌트 코드 테스트

```bash
# 1. 공통 코드 생성
curl -X POST "http://localhost:8080/api/admin/codes" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-Tenant-ID: 1" \
  -H "Content-Type: application/json" \
  -d '{
    "groupKey": "USER_STATUS",
    "codeKey": "ACTIVE",
    "codeName": "활성",
    "tenantId": null
  }'

# 2. 테넌트 코드 생성
curl -X POST "http://localhost:8080/api/admin/codes" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-Tenant-ID: 1" \
  -H "Content-Type: application/json" \
  -d '{
    "groupKey": "USER_STATUS",
    "codeKey": "SUSPENDED",
    "codeName": "일시정지",
    "tenantId": 1
  }'

# 3. 코드 조회 (tenant 우선 → common fallback)
curl -X GET "http://localhost:8080/api/admin/codes?groupKey=USER_STATUS&tenantScope=ALL" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-Tenant-ID: 1"
# → SUSPENDED (tenant), ACTIVE (common) 모두 반환
```

---

## 참고 문서

- [PR06_CODE_CRUD_STANDARDIZATION.md](./PR06_CODE_CRUD_STANDARDIZATION.md): 코드 CRUD 구현 상세
- [PR07_CODE_USAGE_STANDARDIZATION.md](./PR07_CODE_USAGE_STANDARDIZATION.md): CodeUsage 구현 상세
- [ADMIN_API_QUICKREF.md](./ADMIN_API_QUICKREF.md): Admin API 사용법

---

**작성일**: 2026-01-20  
**작성자**: DWP Backend Team
