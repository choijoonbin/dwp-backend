# Admin Menu Resources Migration V3

## 개요

Admin UI Publishing을 위한 메뉴 및 리소스 추가 마이그레이션

## 변경 사항

### 1. 메뉴 추가 (sys_menus)

다음 3개 메뉴를 `menu.admin` 하위에 추가:

| menu_key | menu_name | path | sort_order | icon |
|----------|-----------|------|------------|------|
| menu.admin.menus | 메뉴 관리 | /admin/menus | 106 | solar:folder-bold |
| menu.admin.codes | 코드 관리 | /admin/codes | 107 | solar:code-bold |
| menu.admin.code-usages | 코드 사용정의 | /admin/code-usages | 108 | solar:list-check-bold |

### 2. 기존 메뉴 숨김 처리

- `menu.admin.resources`: `is_visible = 'N'`으로 설정 (하드 삭제 금지)

### 3. 리소스 추가 (com_resources)

다음 3개 리소스를 `com_resources` 테이블에 추가:

- `menu.admin.menus`
- `menu.admin.codes`
- `menu.admin.code-usages`

각 리소스는 `menu.admin` (resource_id=8)의 자식으로 등록됩니다.

### 4. 권한 부여 (com_role_permissions)

ADMIN 역할(role_id=1, tenant_id=1)에 각 리소스에 대해 다음 권한 부여:

- VIEW (permission_id=1)
- USE (permission_id=2)
- EDIT (permission_id=3)

## API 준비 상태

### ✅ Menus CRUD API
- `GET /api/admin/menus` - 목록 조회
- `GET /api/admin/menus/{id}` - 상세 조회
- `POST /api/admin/menus` - 생성
- `PATCH /api/admin/menus/{id}` - 수정
- `DELETE /api/admin/menus/{id}` - 삭제
- `PUT /api/admin/menus/reorder` - 정렬 변경

### ✅ Codes CRUD API
- `GET /api/admin/codes/groups` - 코드 그룹 목록
- `GET /api/admin/codes` - 코드 목록
- `GET /api/admin/codes/all` - 전체 코드 맵
- `GET /api/admin/codes/usage` - 메뉴별 코드 조회
- `POST /api/admin/codes/groups` - 코드 그룹 생성
- `PUT /api/admin/codes/groups/{id}` - 코드 그룹 수정
- `DELETE /api/admin/codes/groups/{id}` - 코드 그룹 삭제
- `POST /api/admin/codes` - 코드 생성
- `PUT /api/admin/codes/{id}` - 코드 수정
- `DELETE /api/admin/codes/{id}` - 코드 삭제

### ✅ Code-Usages CRUD API
- `GET /api/admin/code-usages` - 목록 조회
- `POST /api/admin/code-usages` - 생성
- `PATCH /api/admin/code-usages/{id}` - 수정
- `DELETE /api/admin/code-usages/{id}` - 삭제

## Menu Tree API 확인

`GET /api/auth/menus/tree` 엔드포인트는 권한 기반으로 필터링된 메뉴 트리를 반환합니다.

새로 추가된 메뉴는 ADMIN 역할을 가진 사용자에게만 표시됩니다.

## 마이그레이션 실행

```bash
# Flyway 마이그레이션 자동 실행 (애플리케이션 시작 시)
# 또는 수동 실행:
./gradlew flywayMigrate
```

## 검증 방법

### 1. 데이터베이스 확인

```sql
-- 새로 추가된 메뉴 확인
SELECT menu_key, menu_name, menu_path, sort_order, is_visible
FROM sys_menus
WHERE tenant_id = 1
  AND menu_key IN ('menu.admin.menus', 'menu.admin.codes', 'menu.admin.code-usages')
ORDER BY sort_order;

-- menu.admin.resources 숨김 처리 확인
SELECT menu_key, is_visible
FROM sys_menus
WHERE tenant_id = 1
  AND menu_key = 'menu.admin.resources';

-- 새로 추가된 리소스 확인
SELECT resource_id, key, name
FROM com_resources
WHERE tenant_id = 1
  AND key IN ('menu.admin.menus', 'menu.admin.codes', 'menu.admin.code-usages');

-- 권한 부여 확인
SELECT r.key, p.code, rp.effect
FROM com_role_permissions rp
JOIN com_resources r ON rp.resource_id = r.resource_id
JOIN com_permissions p ON rp.permission_id = p.permission_id
WHERE rp.tenant_id = 1
  AND rp.role_id = 1
  AND r.key IN ('menu.admin.menus', 'menu.admin.codes', 'menu.admin.code-usages')
ORDER BY r.key, p.code;
```

### 2. API 테스트

```bash
# Menu Tree API 호출 (JWT 토큰 필요)
curl -X GET "http://localhost:8080/api/auth/menus/tree" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "X-Tenant-ID: 1"

# 응답에 다음 메뉴가 포함되어야 함:
# - menu.admin.menus
# - menu.admin.codes
# - menu.admin.code-usages
```

## 참고 사항

- 모든 경로는 canonical 형식(`/admin/*`)으로 저장됩니다.
- 프론트엔드에서 `/app/admin/*` alias 처리는 FE에서 담당합니다.
- `menu.admin.resources`는 하드 삭제되지 않고 숨김 처리만 됩니다.
- `AI Workspace`는 `/ai-workspace`로 유지되며 `/admin` 하위로 이동하지 않습니다.
