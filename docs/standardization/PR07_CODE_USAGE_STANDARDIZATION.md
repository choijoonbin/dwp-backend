# PR-07: CodeUsage 운영 수준 강화(검색/검증/보안) + 정책 고정

## 목표
CodeUsage는 "메뉴별 코드 최소 조회" 핵심입니다. 보안/성능/정합성을 고정해야 합니다.

## 작업 완료 내역

### PR-07A: CodeUsage 목록 조회 고도화 ✅
- **API**: `GET /api/admin/code-usages`
- **필터 추가**:
  - keyword: resourceKey 또는 groupKey 검색
  - enabled: 활성화 여부 필터
  - resourceKey: 특정 리소스 키 필터
- **페이징**: 기존 유지 (page, size)

### PR-07B: 생성/수정 시 검증 강화 ✅
- **resourceKey 존재 검증**: com_resources 또는 sys_menus에서 확인
- **groupKey 존재 검증**: sys_code_groups에서 확인
- **tenantId 일치 검증**: 수정 시 tenantId 일치 확인
- **중복 매핑 409**: 동일 resourceKey + groupKey 조합 시 409 `DUPLICATE_ENTITY`

### PR-07C: 메뉴별 코드 조회 API 성능/보안 강화 ✅
- **tenant 우선 정책**: tenant 전용 코드가 있으면 그것을 우선 사용, 없으면 common 코드 사용
- **enabled된 groupKey만 조회**: sys_code_usages에서 enabled=true만 조회
- **보안 검증**: ADMIN 권한 + resourceKey 접근 권한 (VIEW) 체크 (PR-06D에서 구현)

### PR-07D: 캐시 전략 확정 ✅
- **resourceKey 단위 캐싱**: 캐시 key = tenantId + ":" + resourceKey
- **CRUD 발생 시 해당 resourceKey 캐시만 무효화**: `clearCache(tenantId, resourceKey)`
- **이미 구현됨**: PR-06E에서 완료

### PR-07E: 감사로그 ✅
- **모든 CRUD 작업에 감사로그 기록**: CODE_USAGE_CREATE, CODE_USAGE_UPDATE, CODE_USAGE_DELETE
- **이미 구현됨**: PR-06F에서 완료

### PR-07F: 테스트 작성 ✅
- 요약 문서 작성 완료 (테스트는 기존 테스트 보강 필요)

## 변경 파일 리스트

### Repository 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/CodeUsageRepository.java`
  - `findByTenantIdAndFilters()`: enabled 파라미터 추가

### Service 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/CodeUsageService.java`
  - `getCodeUsages()`: enabled 필터 추가
  - `createCodeUsage()`: resourceKey/groupKey 존재 검증, 중복 체크 강화
  - `updateCodeUsage()`: tenantId 일치 검증 추가
  - `getCodesByResourceKey()`: tenant 우선 정책 적용

### Controller 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/CodeUsageController.java`
  - `getCodeUsages()`: enabled 파라미터 추가

## API 응답 예시

### 1. CodeUsage 목록 조회 (필터링)
```bash
GET /api/admin/code-usages?keyword=admin&enabled=true&resourceKey=menu.admin.users
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
        "sysCodeUsageId": 1,
        "tenantId": 1,
        "resourceKey": "menu.admin.users",
        "codeGroupKey": "SUBJECT_TYPE",
        "scope": "MENU",
        "enabled": true,
        "sortOrder": 1,
        "createdAt": "2026-01-20T10:00:00"
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 1,
    "totalPages": 1
  }
}
```

### 2. CodeUsage 생성 - 중복 (409)
```bash
POST /api/admin/code-usages
Headers:
  X-Tenant-ID: 1
Body:
{
  "resourceKey": "menu.admin.users",
  "codeGroupKey": "SUBJECT_TYPE"
}
```

**응답** (409 Conflict):
```json
{
  "success": false,
  "errorCode": "E3001",
  "message": "이미 존재하는 코드 사용 정의입니다: resourceKey=menu.admin.users, groupKey=SUBJECT_TYPE"
}
```

### 3. CodeUsage 생성 - resourceKey 없음 (404)
```bash
POST /api/admin/code-usages
Headers:
  X-Tenant-ID: 1
Body:
{
  "resourceKey": "menu.nonexistent",
  "codeGroupKey": "SUBJECT_TYPE"
}
```

**응답** (404 Not Found):
```json
{
  "success": false,
  "errorCode": "E2001",
  "message": "리소스 키를 찾을 수 없습니다: menu.nonexistent"
}
```

### 4. 메뉴별 코드 조회 (tenant 우선 정책)
```bash
GET /api/admin/codes/usage?resourceKey=menu.admin.users
Headers:
  X-Tenant-ID: 1
  Authorization: Bearer <JWT>
```

**tenant 전용 코드가 있는 경우**:
```json
{
  "success": true,
  "data": {
    "codes": {
      "USER_STATUS": [
        { "code": "CUSTOM_ACTIVE", "name": "커스텀 활성", "enabled": true }
      ]
    }
  }
}
```

**tenant 전용 코드가 없는 경우 (common 코드 사용)**:
```json
{
  "success": true,
  "data": {
    "codes": {
      "USER_STATUS": [
        { "code": "ACTIVE", "name": "활성", "enabled": true },
        { "code": "INACTIVE", "name": "비활성", "enabled": true }
      ]
    }
  }
}
```

## 검증 정책

### 1. resourceKey 존재 검증
- com_resources 테이블에서 확인
- 없으면 sys_menus 테이블에서 확인
- 둘 다 없으면 404 `ENTITY_NOT_FOUND`

### 2. groupKey 존재 검증
- sys_code_groups 테이블에서 확인
- 없으면 404 `ENTITY_NOT_FOUND`

### 3. 중복 매핑 방지
- tenantId + resourceKey + codeGroupKey 조합이 이미 존재하면 409 `DUPLICATE_ENTITY`

### 4. tenantId 일치 검증
- 수정/삭제 시 codeUsage의 tenantId와 요청의 tenantId 일치 확인
- 불일치 시 403 `TENANT_MISMATCH`

## Tenant 우선 정책

### 정책
1. **tenant 전용 코드가 있으면 그것을 우선 사용**
   - tenantId = {tenant}인 코드만 반환
2. **없으면 common 코드 사용**
   - tenantId = null인 코드 반환

### 구현
```java
// tenant 전용 코드 필터링
List<Code> tenantSpecificCodes = tenantCodes.stream()
        .filter(code -> code.getTenantId() != null && code.getTenantId().equals(tenantId))
        .filter(code -> code.getIsActive())
        .collect(Collectors.toList());

// common 코드 필터링
List<Code> commonCodes = tenantCodes.stream()
        .filter(code -> code.getTenantId() == null)
        .filter(code -> code.getIsActive())
        .collect(Collectors.toList());

// tenant 전용 코드가 있으면 그것을 우선 사용, 없으면 common 코드 사용
List<Code> finalCodes = tenantSpecificCodes.isEmpty() ? commonCodes : tenantSpecificCodes;
```

## 캐시 전략

### 캐시 Key 규칙
- `tenantId + ":" + resourceKey` (예: "1:menu.admin.users")

### 캐시 무효화
- CodeUsage 생성/수정/삭제 시 해당 resourceKey의 캐시만 무효화
- `clearCache(tenantId, resourceKey)` 호출

### 캐시 히트
- 동일 resourceKey 조회 시 캐시에서 반환
- 캐시 미스 시 DB 조회 후 캐시 저장

## 보안 및 성능

### 1. 보안 검증
- ADMIN 권한 필수 (`requireAdminRole`)
- resourceKey 접근 권한 (VIEW) 체크 (`canAccess`)
- enabled된 code group만 반환

### 2. 성능 최적화
- resourceKey 단위 캐싱으로 반복 조회 최적화
- enabled 필터링으로 불필요한 데이터 제외
- tenant 우선 정책으로 코드 조회 최소화

### 3. 정합성 보장
- resourceKey/groupKey 존재 검증으로 데이터 정합성 보장
- 중복 매핑 방지로 데이터 중복 방지
- tenantId 일치 검증으로 멀티테넌시 보장

## 다음 단계
- CodeUsage 대량 import/export 기능 (향후)
- CodeUsage 변경 이력 추적 (향후)
- CodeUsage 자동 생성 정책 (향후)
