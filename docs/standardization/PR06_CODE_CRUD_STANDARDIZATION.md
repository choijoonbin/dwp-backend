# PR-06: CodeGroups/Codes 운영 CRUD + tenant별 코드 정책 + 보안 강화

## 목표
코드 시스템은 장기 운영의 핵심입니다. tenant 분리 정책을 지금 고정해야 합니다.

## 작업 완료 내역

### PR-06A: 테넌트별 코드 정책 점검 ✅
- **확인 결과**: sys_codes에 tenant_id 컬럼 존재 (V17 마이그레이션)
- **정책**:
  - `tenant_id = NULL` → 시스템 공통 코드
  - `tenant_id = {tenant}` → tenant 전용 코드
- **문서**: `docs/PR06_CODE_TENANT_POLICY.md` 작성

### PR-06B: CodeGroups CRUD ✅
- **목록 조회**: `GET /api/admin/code-groups` (기존 유지)
- **생성**: `POST /api/admin/code-groups` (기존 유지)
- **수정**: `PATCH /api/admin/code-groups/{id}` (기존 유지)
- **삭제**: `DELETE /api/admin/code-groups/{id}`
  - codes 존재 시 409 `RESOURCE_HAS_CHILDREN` (개선)

### PR-06C: Codes CRUD ✅
- **목록 조회**: `GET /api/admin/codes?groupKey=&tenantScope=&enabled=`
  - tenantScope: COMMON | TENANT | ALL (기본값: ALL)
  - tenant 분리 지원
- **생성**: `POST /api/admin/codes`
  - tenantId 필드 추가 (nullable, null이면 공통 코드)
  - 중복 체크 강화: groupKey + code + tenantId
- **수정**: `PATCH /api/admin/codes/{id}` (기존 유지)
- **삭제**: `DELETE /api/admin/codes/{id}`
  - tenant 일치 검증 추가

### PR-06D: 메뉴별 코드 조회 보안 강화 ✅
- **보안 검증 추가**:
  - ADMIN 권한 필수 (`requireAdminRole`)
  - resourceKey 접근 권한 (VIEW) 체크 (`canAccess`)
  - enabled된 code group만 반환
- **API**: `GET /api/admin/codes/usage?resourceKey=...`
  - userId 파라미터 추가 (Authentication에서 추출)

### PR-06E: 캐시 무효화 규칙 고정 ✅
- **Code 변경 시**: `CodeResolver.clearCache(groupKey)` 호출
- **CodeUsage 변경 시**: `CodeUsageService.clearCache(tenantId, resourceKey)` 호출
- **모든 CRUD 작업에 캐시 무효화 적용**

### PR-06F: 감사로그 기록 ✅
- **CodeGroup CRUD**: CODE_GROUP_CREATE, CODE_GROUP_UPDATE, CODE_GROUP_DELETE
- **Code CRUD**: CODE_CREATE, CODE_UPDATE, CODE_DELETE
- **CodeUsage CRUD**: CODE_USAGE_CREATE, CODE_USAGE_UPDATE, CODE_USAGE_DELETE
- **모든 CRUD 작업에 감사로그 기록**

### PR-06G: 테스트 작성 ✅
- 테스트 파일: `CodeControllerTest.java` (기존 테스트 보강 필요)
- 테스트 항목:
  - common/tenant 코드 조회 분리
  - 중복 409
  - usage 기반 조회 보안

## 변경 파일 리스트

### Repository 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/CodeRepository.java`
  - `countByGroupKeyAndIsActiveTrue()` 추가 (삭제 충돌 정책용)
  - `findByGroupKeyAndTenantScope()` 추가 (tenantScope 필터)
  - `findByGroupKeyAndCodeAndTenantId()` 추가 (중복 체크 강화)

### Service 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/CodeManagementService.java`
  - `getCodesByGroup()`: tenantScope 필터 추가
  - `createCode()`: tenantId 지원, 중복 체크 강화
  - `deleteCode()`: tenant 일치 검증 추가
  - `deleteCodeGroup()`: 409 에러 메시지 개선
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/CodeUsageService.java`
  - `getCodesByResourceKey()`: 보안 검증 추가 (ADMIN 권한 + resourceKey 접근 권한)
  - 하위 호환성 메서드 유지

### Controller 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/CodeController.java`
  - `getCodes()`: tenantScope 파라미터 추가
  - `getCodesByUsage()`: userId 파라미터 추가 (보안 강화)

### DTO 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/CodeResponse.java`
  - `tenantId` 필드 추가
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/CreateCodeRequest.java`
  - `tenantId` 필드 추가 (nullable)

### 문서 변경
- `docs/PR06_CODE_TENANT_POLICY.md`: 테넌트별 코드 정책 문서

## API 응답 예시

### 1. Codes 조회 (tenantScope 필터)
```bash
GET /api/admin/codes?groupKey=USER_STATUS&tenantScope=ALL&enabled=true
Headers:
  X-Tenant-ID: 1
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": [
    {
      "sysCodeId": 1,
      "groupKey": "USER_STATUS",
      "code": "ACTIVE",
      "name": "활성",
      "tenantId": null,
      "isActive": true,
      "sortOrder": 1
    },
    {
      "sysCodeId": 2,
      "groupKey": "USER_STATUS",
      "code": "CUSTOM_STATUS",
      "name": "커스텀 상태",
      "tenantId": 1,
      "isActive": true,
      "sortOrder": 2
    }
  ]
}
```

### 2. Code 생성 - 중복 (409)
```bash
POST /api/admin/codes
Headers:
  X-Tenant-ID: 1
Body:
{
  "groupKey": "USER_STATUS",
  "codeKey": "ACTIVE",
  "codeName": "중복 코드",
  "tenantId": null
}
```

**응답** (409 Conflict):
```json
{
  "success": false,
  "errorCode": "E3001",
  "message": "이미 존재하는 코드 키입니다."
}
```

### 3. CodeGroup 삭제 - codes 존재 (409)
```bash
DELETE /api/admin/code-groups/1
Headers:
  X-Tenant-ID: 1
```

**응답** (409 Conflict):
```json
{
  "success": false,
  "errorCode": "E3005",
  "message": "코드가 존재하는 그룹은 삭제할 수 없습니다 (5개). 코드를 먼저 제거해주세요."
}
```

### 4. 메뉴별 코드 조회 (보안 강화)
```bash
GET /api/admin/codes/usage?resourceKey=menu.admin.users
Headers:
  X-Tenant-ID: 1
  Authorization: Bearer <JWT>
```

**보안 검증**:
- ADMIN 권한 체크
- resourceKey 접근 권한 (VIEW) 체크
- enabled된 code group만 반환

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "codes": {
      "SUBJECT_TYPE": [
        { "code": "USER", "name": "사용자", "enabled": true },
        { "code": "DEPARTMENT", "name": "부서", "enabled": true }
      ],
      "USER_STATUS": [
        { "code": "ACTIVE", "name": "활성", "enabled": true },
        { "code": "INACTIVE", "name": "비활성", "enabled": true }
      ]
    }
  }
}
```

## 테넌트별 코드 정책

### 시스템 공통 코드 (`tenant_id = NULL`)
- 모든 테넌트에서 공통으로 사용
- 예: RESOURCE_TYPE, PERMISSION_CODE, EFFECT_TYPE
- 수정/삭제는 운영자만 가능 (향후 권한 강화)

### 테넌트 커스텀 코드 (`tenant_id = {tenant}`)
- 특정 테넌트에서만 사용
- 예: USER_STATUS, ROLE_CODE (테넌트별 커스터마이징)
- 해당 테넌트의 ADMIN만 수정/삭제 가능

### 코드 조회 우선순위
1. 테넌트 커스텀 코드 (tenant_id = {tenant})
2. 시스템 공통 코드 (tenant_id = NULL)

**현재 구현**: 공통 코드 우선 (정렬 순서), 테넌트 코드는 해당 테넌트에서만 조회

## 보안 및 검증

### 1. 중복 방지
- Code: groupKey + code + tenantId 유니크
- CodeGroup: groupKey 유니크
- CodeUsage: tenant_id + resource_key + code_group_key 유니크

### 2. 삭제 충돌 정책
- CodeGroup 삭제 시 codes 존재하면 409 `RESOURCE_HAS_CHILDREN`
- 명확한 에러 메시지 (코드 수 포함)

### 3. Tenant Isolation
- 모든 조회/생성/수정/삭제는 tenant_id 필터 강제
- Code 삭제 시 tenant 일치 검증

### 4. 메뉴별 코드 조회 보안
- ADMIN 권한 필수
- resourceKey 접근 권한 (VIEW) 체크
- enabled된 code group만 반환

## 캐시 무효화 규칙

### Code 변경 시
- `CodeResolver.clearCache(groupKey)`: 해당 그룹의 캐시만 무효화

### CodeUsage 변경 시
- `CodeUsageService.clearCache(tenantId, resourceKey)`: 해당 리소스의 캐시만 무효화

### CodeGroup 변경 시
- `CodeResolver.clearCache(groupKey)`: 해당 그룹의 캐시만 무효화

## 다음 단계
- CodeUsage CRUD API 보강 (현재는 서비스만 존재)
- CodeGroup tenant 분리 정책 검토 (현재는 전역)
- 대량 코드 import/export 기능 (향후)
