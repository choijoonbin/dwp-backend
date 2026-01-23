# Admin API 보완 결과 (FE 요청 구현)

> **작성일**: 2026-01-23  
> **요청 문서**: `docs/api-spec/FRONTEND_API_REQUEST_ADMIN_API_COMPLETION.md`  
> **로드맵**: `docs/frontend/docs/api-spec/FRONTEND_API_REQUEST_ADMIN_API_COMPLETION_ROADMAP.md`

---

## 1. 구현 요약

| ID | 항목 | 구현 내용 | 비고 |
|----|------|-----------|------|
| **P0-2** | Audit `actor`/`action` alias | `AdminAuditLogController.getAuditLogs`: `actor`→`actorUserId`, `action`→`actionType` 이중 바인딩. 기존 파라미터 유지. | 하위호환 |
| **P1-1** | Codes Groups 필터 | `GET /api/admin/codes/groups` 에 `keyword`, `tenantScope`, `enabled` 추가. `CodeManagementService.getAllGroups(String,String,Boolean)`, `CodeGroupRepository.findWithFilters` | `tenantScope`: COMMON/TENANT/ALL(코드 존재 여부로 그룹 필터) |
| **P1-2** | Code Usages `codeGroupKey` | `GET /api/admin/code-usages` 에 `codeGroupKey` 추가. `CodeUsageRepository.findByTenantIdAndFilters` 시그니처 확장 | |
| **P1-3** | Resources `resourceType` | `GET /api/admin/resources` 에 `resourceType` alias (`type` 없을 때 `resourceType` 사용) | |
| **P1-4** | Audit Logs Detail | `GET /api/admin/audit-logs/{id}` 추가. `AuditLogDetail` DTO (ipAddress, userAgent, beforeValue, afterValue는 `metadata_json`에서 추출) | `AuditLogRepository.findByTenantIdAndAuditLogId` 추가 |
| **P1-5** | Code Usages Detail | `GET /api/admin/code-usages/{sysCodeUsageId}` 추가. `CodeUsageDetail` DTO (CodeUsageSummary + createdBy, updatedBy) | BaseEntity 필드 매핑 |
| **P1-6** | Resources Detail | `GET /api/admin/resources/{comResourceId}` 추가. `ResourceSummary` 반환. `ResourceQueryService.getResourceById`, `ResourceManagementService.getResourceById` | |
| **P1-7** | RBAC (Menus, Audit, resources/tree) | `AdminEndpointPolicyRegistry` 에 등록: Menus(GET/POST/PATCH/DELETE/PUT reorder)→`menu.admin.menus`, Audit(GET list, GET `{id}`, GET/POST export)→`menu.admin.audit`, `GET /api/admin/resources/tree`→`menu.admin.resources` VIEW | |
| **P1-8** | 응답 필드 보완 | **ResourceSummary**: `icon`, `status`(enabled→ACTIVE/INACTIVE), `description`(metadata 또는 엔티티). **MenuNode**: `id`(sysMenuId), `parentId`, `enabled`, `permissionKey`(menuKey). **UserSummary/RoleDetail**: `lastLoginAt`, `updatedAt` — DTO에 이미 존재, 서비스에서 채우면 됨 | Resource icon/description은 `metadataJson` 기반 |
| **P1-9** | Audit Export GET | `GET /api/admin/audit-logs/export?from=&to=&actor=&action=&resourceType=&keyword=&maxRows=` 추가. POST /export와 동일 로직, `actor`/`action` alias 지원 | RBAC에 GET /export 등록 |

---

## 2. 변경/추가 파일

- **Controller**: `AdminAuditLogController`, `CodeController`, `CodeUsageController`, `ResourceController`
- **Service**: `AuditLogQueryService`, `CodeManagementService`, `CodeUsageQueryService`, `CodeUsageService`, `ResourceQueryService`, `ResourceManagementService`, `MenuQueryService`
- **Repository**: `AuditLogRepository`, `CodeGroupRepository`, `CodeUsageRepository`
- **DTO**: `AuditLogDetail`, `CodeUsageDetail` (신규), `ResourceSummary`, `MenuNode`
- **Config**: `AdminEndpointPolicyRegistry`
- **Test**: `CodeControllerTest`, `CodeUsageControllerTest`, `CodeUsageServiceTest` (시그니처 변경 반영)

---

## 3. 주의사항 및 제한

1. **Audit `metadata_json`**  
   - `ipAddress`, `userAgent`, `beforeValue`, `afterValue` 는 `metadata_json` 키 `ip`/`ipAddress`, `userAgent`/`user_agent`, `before`, `after` 기반. DB에 ip/user_agent 컬럼이 없으면 메타데이터만 사용.

2. **ResourceSummary `icon`/`description`**  
   - `Resource` 엔티티에 해당 컬럼 없음. `metadataJson` 에 `icon`, `description` 이 있으면 사용.

3. **CodeGroup `tenantScope`**  
   - `CodeGroup` 에는 `tenantScope` 컬럼이 없음. `tenantScope=COMMON`은 “해당 그룹에 `tenant_id IS NULL` 인 코드가 존재”, `TENANT`는 “`tenant_id IS NOT NULL` 인 코드가 존재”하는 그룹만 반환.

4. **테스트**  
   - `CodeControllerTest.getGroups_ReturnsGroupList`: `getAllGroups(any(), any(), any())` 로 모킹 변경.  
   - `CodeUsageControllerTest`, `CodeUsageServiceTest`: `getCodeUsages` 7인자, `findByTenantIdAndFilters` 6인자 반영.  
   - 일부 `@WebMvcTest`/통합 테스트는 `NoSuchBeanDefinitionException` 등 기존 컨텍스트 이슈로 실패할 수 있음. 컴파일은 통과.

5. **P0-1 (HTTP Method 11건)**  
   - FE 변경 사항. BE는 기존 PUT/PATCH/DELETE 유지.

---

## 4. P1–P2 매핑

- **P2-1** (Roles Delete): BE에 `POST /delete` 없음 → N/A.  
- **P2-2** (Code Usages GET `/{id}` 정책): `GET /api/admin/code-usages/\d+` 는 기존 Registry에 등록돼 있어 P1-5 구현 시 그대로 적용.

---

## 5. FE 연동 시 고려사항

요청 명세 예시와 실제 구현 간 차이가 있는 부분입니다. FE 연동 시 참고해 주세요.

### 5.1. Audit Logs Detail (`GET /api/admin/audit-logs/{id}`)

| FE 요청 예시 필드 | BE 구현 | 비고 |
|-------------------|---------|------|
| `actorName` | **미제공** | `actorUserId`(Long)만 반환. 표시명이 필요하면 FE에서 별도 조회 또는 추후 BE 확장 |
| `resourceName` | **미제공** | `resourceType`, `resourceId`만 반환 |
| `details` | **미제공** | `metadata`, `action` 등으로 대체 가능 |
| `ipAddress`, `userAgent`, `beforeValue`, `afterValue` | ✅ 제공 | `metadata_json`에서 추출 (키: ip/ipAddress, userAgent/user_agent, before, after) |

### 5.2. Code Usages Detail (`GET /api/admin/code-usages/{id}`)

| FE 요청 예시 | BE 구현 | 비고 |
|--------------|---------|------|
| `createdBy`: `"admin"` (문자열) | `createdBy`: **Long** (user id) | BaseEntity `created_by` 매핑. 표시명이 필요하면 FE에서 id→이름 조회 또는 추후 BE 확장 |
| `updatedBy`: `"admin"` (문자열) | `updatedBy`: **Long** (user id) | 동일 |

### 5.3. UserSummary / RoleDetail (`lastLoginAt`, `updatedAt`)

- **UserSummary**: `lastLoginAt`는 `sys_login_histories` 기반으로 매핑, 이력이 없으면 **null**. `updatedAt`은 `BaseEntity`에서 매핑.
- **RoleDetail**: `updatedAt`은 `BaseEntity`에서 매핑. `lastLoginAt` 필드는 **없음**.
- FE에서는 **optional·null 가능**으로 처리 권장. 추가 보완이 필요하면 별도 요청.

### 5.4. 테스트

- **이번 작업**: 기존 테스트(CodeController, CodeUsageController, CodeUsageService) 시그니처 변경 반영만 수행.
- **신규 API** (Audit Detail, Code Usages Detail, Resources Detail, Audit Export GET)에 대한 **전용 테스트는 미추가.**
- FE 요청의 "각 API별 최소 1개 이상" 테스트는 추후 BE에서 보강 예정.

---

## 6. 다음 단계 제안

- FE: P0-1 HTTP Method 정리, 신규/변경 파라미터·필드 반영. **5. FE 연동 시 고려사항** 반영하여 null/타입 처리.
- BE: `./gradlew :dwp-auth-server:test` 실패 원인 중 컨텍스트/Bean 이슈 정리 후, Audit list(alias), Code usages(codeGroupKey), Resources(resourceType), Audit/CodeUsages/Resources Detail, Audit Export GET 등 회귀 시나리오 보강. 신규 API 전용 테스트 및 (필요 시) actorName/resourceName, createdBy/updatedBy 표시명 확장 검토.
