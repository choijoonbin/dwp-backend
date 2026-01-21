# PR-04: Resources 운영 CRUD 완성

## 목표
Resources는 권한/이벤트/추적의 핵심 마스터 데이터입니다. 운영 수준 CRUD를 완성하여 Monitoring과 RBAC의 안정성을 보장합니다.

## 작업 완료 내역

### PR-04A: 현재 스키마/데이터 점검 ✅
- **컬럼 확인**: resource_category, resource_kind, event_key, event_actions, tracking_enabled, ui_scope 모두 존재
- **Seed 데이터**: V16 마이그레이션으로 기존 데이터 마이그레이션 완료
- **Menu Tree API**: com_resources(권한) + sys_menus(메타) 이중 구조 사용
- **문서**: `docs/PR04_RESOURCE_CRUD_SUMMARY.md` 작성

### PR-04B: Admin Resources 목록 조회 API ✅
- **API**: `GET /api/admin/resources`
- **Query 파라미터**:
  - `page`, `size`
  - `keyword` (key/name 검색)
  - `resourceCategory` (코드 기반)
  - `resourceKind` (코드 기반)
  - `trackingEnabled` (true/false) ✅ 추가
  - `enabled` (true/false)
- **Response**: `ApiResponse<Page<ResourceSummary>>`
- **정렬**: `created_at desc` 기본 정렬 ✅ 변경
- **필터**: tenant_id 필터 무조건 적용

### PR-04C: Admin Resources 생성 API ✅
- **API**: `POST /api/admin/resources`
- **Request 필드**:
  - `resourceKey` (유니크) ✅ 예: menu.admin.monitoring / btn.mail.send
  - `resourceName`
  - `resourceCategory` (코드 기반 검증) ✅
  - `resourceKind` (코드 기반 검증) ✅
  - `parentResourceId` (nullable, tenant 일치 검증) ✅
  - `meta` (JSON)
  - `trackingEnabled` (boolean)
  - `eventActions` (string[], UI_ACTION 코드 기반 검증) ✅
  - `enabled` (boolean)
- **규칙**:
  - 중복 resourceKey → 409 RESOURCE_KEY_DUPLICATED ✅
  - resourceCategory/resourceKind/eventActions 모두 CodeResolver 검증 ✅
  - parentResourceId 존재 시 tenant 일치 검증 ✅
  - eventKey 자동 생성 (resourceKey:action 형식) ✅

### PR-04D: Admin Resources 수정 API ✅
- **API**: `PATCH /api/admin/resources/{comResourceId}`
- **수정 가능**:
  - `name`, `meta`, `trackingEnabled`, `eventActions`, `enabled`, `parentResourceId`, `resourceCategory`, `resourceKind`
- **금지**:
  - `resourceKey` 변경 금지 (운영 위험) ✅ → 400 INVALID_STATE
  - 자기 자신을 부모로 설정 금지 ✅

### PR-04E: Admin Resources 삭제 정책 ✅
- **API**: `DELETE /api/admin/resources/{comResourceId}`
- **정책**:
  - Soft delete 권장 (enabled=false) ✅
  - 하위 리소스 존재 시 409 RESOURCE_HAS_CHILDREN ✅
  - 메시지: "하위 리소스가 존재합니다 (X개). 하위 리소스를 먼저 제거해주세요."

### PR-04F: 감사로그 & 권한/메뉴 연계 반영 ✅
- **감사로그**: 생성/수정/삭제 시 com_audit_logs 기록 ✅
- **캐시 무효화**: resource 변경 시 캐시 무효화 메서드 호출 ✅
  - menus/tree cache invalidate (참고용, 현재는 캐시 TTL 신뢰)
  - permissions cache invalidate (해당 리소스를 가진 사용자, 향후 최적화)
- **문서화**: FE에서 권한 변경 후 `/api/auth/permissions`, `/api/auth/menus/tree` 재조회 권장

## 변경 파일 리스트

### Core 변경
- `dwp-core/src/main/java/com/dwp/core/common/ErrorCode.java`
  - `RESOURCE_KEY_DUPLICATED` 추가 (E3004)
  - `RESOURCE_HAS_CHILDREN` 추가 (E3005)

### Repository 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/ResourceRepository.java`
  - `findByTenantIdAndFilters()`: trackingEnabled 필터 추가, created_at desc 정렬
  - `countByTenantIdAndParentResourceId()` 추가 (PR-04E)

### Service 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/ResourceManagementService.java`
  - `getResources()`: trackingEnabled 파라미터 추가
  - `createResource()`: resourceCategory/resourceKind/eventActions 코드 검증, 중복 409, tenant 일치 검증
  - `updateResource()`: resourceKey 변경 금지, resourceCategory/resourceKind/eventActions 수정 가능
  - `deleteResource()`: 하위 리소스 존재 시 409
  - `invalidateResourceCache()`: 캐시 무효화 메서드 추가 (PR-04F)

### DTO 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/CreateResourceRequest.java`
  - `resourceCategory`, `resourceKind`, `eventActions`, `trackingEnabled` 필드 추가
  - 하위 호환성: `resourceType`, `parentResourceKey` 유지
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/UpdateResourceRequest.java`
  - `resourceCategory`, `resourceKind`, `eventActions`, `trackingEnabled`, `parentResourceId` 필드 추가
  - 하위 호환성: 기존 필드 유지

### Controller 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/ResourceController.java`
  - `getResources()`: trackingEnabled 파라미터 추가

## API 응답 예시

### 1. 리소스 목록 조회
```bash
GET /api/admin/resources?page=1&size=20&resourceCategory=MENU&trackingEnabled=true
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
        "comResourceId": 10,
        "resourceKey": "menu.admin.users",
        "resourceName": "사용자 관리",
        "type": "MENU",
        "enabled": true,
        "createdAt": "2026-01-20T10:00:00"
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 13,
    "totalPages": 1
  }
}
```

### 2. 리소스 생성 - 중복 (409)
```bash
POST /api/admin/resources
Headers:
  X-Tenant-ID: 1
Body:
{
  "resourceKey": "menu.admin.users",
  "resourceName": "중복 리소스",
  "resourceCategory": "MENU",
  "resourceKind": "PAGE"
}
```

**응답** (409 Conflict):
```json
{
  "success": false,
  "errorCode": "E3004",
  "message": "이미 존재하는 리소스 키입니다."
}
```

### 3. 리소스 수정 - resourceKey 변경 금지 (400)
```bash
PATCH /api/admin/resources/10
Headers:
  X-Tenant-ID: 1
Body:
{
  "resourceKey": "menu.admin.users.new"
}
```

**응답** (400 Bad Request):
```json
{
  "success": false,
  "errorCode": "E3002",
  "message": "리소스 키 변경은 허용되지 않습니다. 별도 API를 사용해주세요."
}
```

### 4. 리소스 삭제 - 하위 리소스 존재 (409)
```bash
DELETE /api/admin/resources/8
Headers:
  X-Tenant-ID: 1
```

**응답** (409 Conflict):
```json
{
  "success": false,
  "errorCode": "E3005",
  "message": "하위 리소스가 존재합니다 (5개). 하위 리소스를 먼저 제거해주세요."
}
```

## 보안 및 검증

### 1. 코드 기반 검증
- `resourceCategory`: CodeResolver(RESOURCE_CATEGORY)
- `resourceKind`: CodeResolver(RESOURCE_KIND)
- `eventActions`: CodeResolver(UI_ACTION) - 각 action 검증

### 2. 중복 방지
- Resource key: tenant_id + key 기준 중복 체크 (type 무관)
- DB unique constraint: (tenant_id, type, key)

### 3. 삭제 충돌 정책
- 하위 리소스 존재 시 409 RESOURCE_HAS_CHILDREN
- 명확한 에러 메시지 (하위 리소스 수 포함)

### 4. Tenant Isolation
- 모든 조회/생성/수정/삭제는 tenant_id 필터 강제
- parentResourceId는 tenant 일치 검증

## 다음 단계
- Resource 변경 시 해당 리소스를 가진 사용자만 선택적으로 캐시 무효화 (성능 최적화)
- Menu tree cache 구현 시 Resource 변경 시 무효화 연계
- ResourceSummary에 resourceCategory, resourceKind, trackingEnabled 필드 추가 (향후)
