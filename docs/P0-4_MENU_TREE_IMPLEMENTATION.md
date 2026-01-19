# P0-4: 메뉴 트리(Menu Tree) 관리 구조 도입 완료 보고서

## 개요
메뉴 트리 관리 구조를 도입하고 권한 기반 메뉴 트리 API를 제공하여 프론트엔드 사이드바 렌더링을 지원합니다.

---

## 1. 추가된 Flyway 마이그레이션

### V6__create_sys_menus.sql
- **목적**: 메뉴 트리 메타 테이블 생성
- **테이블**: `sys_menus`
- **주요 컬럼**:
  - `sys_menu_id` (PK)
  - `tenant_id` (테넌트 식별자)
  - `menu_key` (메뉴 키, com_resources.resource_key와 매칭)
  - `menu_name` (화면 노출명)
  - `menu_path` (라우트 경로)
  - `menu_icon` (아이콘 키)
  - `menu_group` (메뉴 그룹: MANAGEMENT/APPS)
  - `parent_menu_key` (상위 메뉴 키, 루트면 NULL)
  - `sort_order` (정렬 순서)
  - `depth` (메뉴 깊이: 1=루트, 2=하위, 3=하하위)
  - `is_visible` (노출 여부: Y/N)
  - `is_enabled` (활성화 여부: Y/N)
  - `description` (메뉴 설명)
- **인덱스**: tenant_id, menu_key, parent_menu_key, tenant_id+parent_menu_key

### V7__seed_sys_menus.sql
- **목적**: dev tenant 기준 기본 메뉴 트리 seed 데이터 추가
- **방식**: UPSERT (ON CONFLICT DO UPDATE)로 안정성 확보
- **포함 메뉴**:
  - 루트 메뉴 (4개):
    - `menu.dashboard` (APPS)
    - `menu.mail` (APPS)
    - `menu.ai-workspace` (APPS)
    - `menu.admin` (MANAGEMENT)
  - 하위 메뉴:
    - `menu.mail.inbox`, `menu.mail.sent`
    - `menu.admin.monitoring`, `menu.admin.users`, `menu.admin.roles`, `menu.admin.resources`, `menu.admin.audit`

---

## 2. sys_menus 테이블 스키마

```sql
CREATE TABLE sys_menus (
    sys_menu_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    menu_key VARCHAR(255) NOT NULL,
    menu_name VARCHAR(200) NOT NULL,
    menu_path VARCHAR(500),
    menu_icon VARCHAR(100),
    menu_group VARCHAR(50),
    parent_menu_key VARCHAR(255),
    sort_order INTEGER NOT NULL DEFAULT 0,
    depth INTEGER NOT NULL DEFAULT 1,
    is_visible CHAR(1) NOT NULL DEFAULT 'Y',
    is_enabled CHAR(1) NOT NULL DEFAULT 'Y',
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_menus_tenant_key UNIQUE (tenant_id, menu_key)
);
```

**주요 특징**:
- FK 제약 없음 (논리적 참조만)
- `parent_menu_key`는 문자열로 관리 (key 기반 트리 복원)
- 모든 컬럼에 COMMENT 작성 완료
- 멀티테넌시 지원 (tenant_id 필터링)

---

## 3. Seed 데이터 요약

### 루트 메뉴 (depth=1)
| menu_key | menu_name | path | group | sort_order |
|----------|-----------|------|-------|------------|
| menu.dashboard | Dashboard | /dashboard | APPS | 10 |
| menu.mail | Mail | /mail | APPS | 20 |
| menu.ai-workspace | AI Workspace | /ai-workspace | APPS | 30 |
| menu.admin | Admin | /admin | MANAGEMENT | 100 |

### 하위 메뉴 (depth=2)
| menu_key | menu_name | path | parent | sort_order |
|----------|-----------|------|--------|------------|
| menu.mail.inbox | Inbox | /mail/inbox | menu.mail | 21 |
| menu.mail.sent | Sent | /mail/sent | menu.mail | 22 |
| menu.admin.monitoring | 통합 모니터링 | /admin/monitoring | menu.admin | 101 |
| menu.admin.users | 사용자 관리 | /admin/users | menu.admin | 102 |
| menu.admin.roles | 역할 관리 | /admin/roles | menu.admin | 103 |
| menu.admin.resources | 리소스 관리 | /admin/resources | menu.admin | 104 |
| menu.admin.audit | 감사 로그 | /admin/audit | menu.admin | 105 |

---

## 4. 구현된 컴포넌트

### 엔티티
- `Menu` (`com.dwp.services.auth.entity.Menu`)
  - sys_menus 테이블 매핑
  - `isActiveAndVisible()` 헬퍼 메서드 제공

### Repository
- `MenuRepository` (`com.dwp.services.auth.repository.MenuRepository`)
  - `findByTenantIdAndActive()`: 활성화된 메뉴 조회
  - `findByTenantIdAndMenuKeyIn()`: 메뉴 키 목록으로 조회
  - `findByTenantIdAndMenuKey()`: 단일 메뉴 조회
  - `findRootMenusByTenantId()`: 루트 메뉴 조회
  - `findByTenantIdAndParentMenuKey()`: 자식 메뉴 조회

### DTO
- `MenuNode` (`com.dwp.services.auth.dto.MenuNode`)
  - 메뉴 트리 노드 구조
  - 자식 메뉴 목록 포함
- `MenuTreeResponse` (`com.dwp.services.auth.dto.MenuTreeResponse`)
  - 메뉴 트리 응답
  - 그룹별 분류 포함 (`MenuGroup`)

### Service
- `MenuService` (`com.dwp.services.auth.service.MenuService`)
  - `getMenuTree()`: 권한 기반 메뉴 트리 구성
  - 로직:
    1. 사용자 역할 ID 목록 조회
    2. VIEW=ALLOW 권한의 MENU 리소스 키 수집
    3. sys_menus에서 메뉴 메타 조회
    4. 부모 메뉴 자동 포함 (자식이 허용되면 부모도 포함)
    5. parent_menu_key 기반 트리 구성
    6. sort_order 기준 정렬
    7. 그룹별 분류

### Controller
- `MenuController` (`com.dwp.services.auth.controller.MenuController`)
  - `GET /api/auth/menus/tree`: 메뉴 트리 조회 API

---

## 5. 메뉴 트리 API 명세

### 엔드포인트
```
GET /api/auth/menus/tree
```

### 요청 헤더
- `Authorization`: Bearer JWT (필수)
- `X-Tenant-ID`: 테넌트 ID (필수)

### 응답 예시

```json
{
  "status": 200,
  "message": "success",
  "data": {
    "menus": [
      {
        "menuKey": "menu.dashboard",
        "menuName": "Dashboard",
        "path": "/dashboard",
        "icon": "solar:home-2-bold",
        "group": "APPS",
        "depth": 1,
        "sortOrder": 10,
        "children": []
      },
      {
        "menuKey": "menu.mail",
        "menuName": "Mail",
        "path": "/mail",
        "icon": "solar:letter-bold",
        "group": "APPS",
        "depth": 1,
        "sortOrder": 20,
        "children": [
          {
            "menuKey": "menu.mail.inbox",
            "menuName": "Inbox",
            "path": "/mail/inbox",
            "icon": "solar:inbox-bold",
            "group": "APPS",
            "depth": 2,
            "sortOrder": 21,
            "children": []
          },
          {
            "menuKey": "menu.mail.sent",
            "menuName": "Sent",
            "path": "/mail/sent",
            "icon": "solar:letter-opened-bold",
            "group": "APPS",
            "depth": 2,
            "sortOrder": 22,
            "children": []
          }
        ]
      },
      {
        "menuKey": "menu.admin",
        "menuName": "Admin",
        "path": "/admin",
        "icon": "solar:settings-bold",
        "group": "MANAGEMENT",
        "depth": 1,
        "sortOrder": 100,
        "children": [
          {
            "menuKey": "menu.admin.monitoring",
            "menuName": "통합 모니터링",
            "path": "/admin/monitoring",
            "icon": "solar:chart-2-bold",
            "group": "MANAGEMENT",
            "depth": 2,
            "sortOrder": 101,
            "children": []
          },
          {
            "menuKey": "menu.admin.users",
            "menuName": "사용자 관리",
            "path": "/admin/users",
            "icon": "solar:users-group-rounded-bold",
            "group": "MANAGEMENT",
            "depth": 2,
            "sortOrder": 102,
            "children": []
          }
        ]
      }
    ],
    "groups": [
      {
        "groupCode": "APPS",
        "groupName": "앱",
        "menus": [...]
      },
      {
        "groupCode": "MANAGEMENT",
        "groupName": "관리",
        "menus": [...]
      }
    ]
  }
}
```

---

## 6. 기존 API 호환성 유지

### `/api/auth/permissions` API
- 기존 응답 형식 유지
- **개선사항**: MENU 타입 리소스의 경우 `sys_menus.menu_name`을 `resourceName`으로 사용
  - 기존: `com_resources.name` 사용
  - 개선: `sys_menus.menu_name` 우선 사용 (존재 시)

---

## 7. 프론트엔드 연동 방법

### 권장 방식: 메뉴 트리 API 사용
```typescript
// 기존 방식 (권장하지 않음)
const permissions = await getPermissions();
// dotted key를 파싱하여 트리 구성 필요

// 새로운 방식 (권장)
const menuTree = await fetch('/api/auth/menus/tree', {
  headers: {
    'Authorization': `Bearer ${token}`,
    'X-Tenant-ID': tenantId
  }
});
// 이미 트리 구조로 반환되므로 바로 사용 가능
```

### 사이드바 렌더링 예시
```typescript
function Sidebar({ menuTree }: { menuTree: MenuTreeResponse }) {
  return (
    <nav>
      {menuTree.menus.map(menu => (
        <MenuItem key={menu.menuKey} menu={menu} />
      ))}
    </nav>
  );
}

function MenuItem({ menu }: { menu: MenuNode }) {
  return (
    <div>
      <Link to={menu.path}>
        <Icon icon={menu.icon} />
        {menu.menuName}
      </Link>
      {menu.children && menu.children.length > 0 && (
        <ul>
          {menu.children.map(child => (
            <MenuItem key={child.menuKey} menu={child} />
          ))}
        </ul>
      )}
    </div>
  );
}
```

---

## 8. 테스트 방법

### 1. 서버 재시작
```bash
./gradlew :dwp-auth-server:bootRun
```

### 2. Admin 계정으로 로그인
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "admin1234!",
    "tenantId": "dev"
  }'
```

### 3. 메뉴 트리 조회
```bash
curl -X GET http://localhost:8080/api/auth/menus/tree \
  -H "Authorization: Bearer <TOKEN>" \
  -H "X-Tenant-ID: 1"
```

### 4. 확인 사항
- ✅ admin 하위 메뉴들이 children으로 포함되어 반환되는지 확인
- ✅ mail.inbox / mail.sent가 mail children으로 반환되는지 확인
- ✅ tenant_id 다르면 메뉴가 조회되지 않는지 확인
- ✅ sort_order 기준 정렬이 올바른지 확인

---

## 9. 향후 확장 포인트

### 권한 체크 확장
- `is_visible=Y` && `permission(VIEW)=ALLOW` 인 경우만 노출
- 현재는 권한만 체크하지만, 향후 `is_visible`도 함께 고려 가능

### 메뉴 관리 UI
- Admin 메뉴에서 sys_menus 테이블 CRUD 기능 추가 가능
- 메뉴 순서 변경, 그룹 변경 등 관리 기능 확장 가능

---

## 10. 완료 체크리스트

- [x] V6__create_sys_menus.sql 생성
- [x] V7__seed_sys_menus.sql 생성
- [x] Menu 엔티티 및 Repository 생성
- [x] MenuTreeResponse, MenuNode DTO 생성
- [x] MenuService 메뉴 트리 구성 로직 구현
- [x] MenuController GET /api/auth/menus/tree API 구현
- [x] 기존 AuthService 호환성 개선 (menu_name 우선 사용)
- [x] 모든 컬럼 COMMENT 작성
- [x] 멀티테넌시 필터링 적용
- [x] 부모 메뉴 자동 포함 로직 구현
- [x] 정렬 및 그룹화 로직 구현

---

## 11. 참고 파일

- `dwp-auth-server/src/main/resources/db/migration/V6__create_sys_menus.sql`
- `dwp-auth-server/src/main/resources/db/migration/V7__seed_sys_menus.sql`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/entity/Menu.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/MenuRepository.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/MenuNode.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/MenuTreeResponse.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/MenuService.java`
- `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/MenuController.java`
