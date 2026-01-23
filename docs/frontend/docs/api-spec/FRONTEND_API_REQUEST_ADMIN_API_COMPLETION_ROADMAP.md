# Admin API 보완 로드맵 (BE 검증 반영)

> **용도**: `FRONTEND_API_REQUEST_ADMIN_API_COMPLETION` 요청에 대한 백엔드 작업 로드맵. **검토 후 피드백 부탁드립니다.**  
> **작성일**: 2026-01-23  
> **요청 문서 (BE)**: `docs/api-spec/FRONTEND_API_REQUEST_ADMIN_API_COMPLETION.md`  
> **목적**: 프론트 3건 문서(Gap 분석, 요청 명세, 남은 작업) 분석 + 백엔드 실코드 검증 기반 로드맵

---

## 1. 문서 가용성 및 검증 요약

| 문서 | 경로 (frontend-src) | 확인 |
|------|---------------------|------|
| Gap 분석 | `docs/specs/admin/ADMIN_API_GAP_ANALYSIS.md` | ✅ |
| 백엔드 요청 명세 | `docs/api-spec/FRONTEND_API_REQUEST_ADMIN_API_COMPLETION.md` (BE `docs/api-spec/`) | ✅ |
| 남은 작업 백로그 | `docs/specs/admin/ADMIN_REMAINING_WORK.md` | ✅ |

**백엔드 검증**: `dwp-auth-server` Controller/DTO/Entity/AdminEndpointPolicyRegistry 기준으로 재확인함.  
일부 Gap 분석 내용과 BE 실체가 다름(아래 보정 참고).

---

## 2. BE 검증 보정 사항

| 구분 | FE Gap/요청 | BE 실체 | 보정 |
|------|-------------|---------|------|
| **Roles Delete** | DELETE + POST /delete 중복 | `RoleController`: `DELETE /{id}` 만 존재, `POST /delete` 없음 | P2 "중복 제거" → **N/A** (FE가 POST/delete 호출 시 405. FE를 `DELETE`로 수정하면 해결) |
| **Resources Detail** | BE 존재 | `ResourceController`: `GET /{id}` **없음** | **추가 필요** (P1) |
| **Code Usages Detail** | BE가 Summary만 반환 | `CodeUsageController`: `GET /{id}` **없음** | **신규 GET /{id} + Detail DTO** (P1) |
| **Code Usages** | `codeGroupKey` query 미지원 | `CodeUsageController` GET: `resourceKey`, `keyword`, `enabled` 만 | `codeGroupKey` 추가 (P1) |
| **Codes groups** | `keyword`, `tenantScope`, `enabled` 미지원 | `CodeController` GET /groups: 파라미터 없음 | 3개 query 추가 (P1) |
| **Resources List** | `resourceType` | `ResourceController` GET: `type` 만 | `resourceType` alias 추가 (P1) |
| **Audit List** | `actor`, `action` | `AdminAuditLogController`: `actorUserId`, `actionType` | `actor`/`action` alias (P0) |
| **Audit Export** | GET + query | `POST /export` + body | GET 추가 또는 FE가 POST+body 사용 (P1) |
| **Audit Detail** | 미존재 | 미존재 | `GET /audit-logs/{id}` 신규 (P1) |
| **RBAC** | Resources, Menus, Codes, Code Usages, Audit | `AdminEndpointPolicyRegistry`: Codes, Code-usages, Users, Roles, **Resources** 있음. **Menus, Audit-logs, resources/tree** **없음** | Menus, Audit, resources/tree 정책 등록 (P1) |

---

## 3. 로드맵 (우선순위·일정·담당 구분)

### 3.1. P0 (긴급) — FE/BE 협의 선행

| ID | 항목 | 조치 | 담당 | 비고 |
|----|------|------|------|------|
| **P0-1** | **HTTP Method 불일치 (11건)** | **FE 수정** (BE REST 유지): Resources PUT/DELETE, Menus PATCH/DELETE/PUT reorder, Codes PUT/DELETE, Code Usages PATCH/DELETE | FE | 명세서 옵션 A 채택. `admin-iam-api.ts`, `code-usage-api.ts` |
| **P0-2** | **Audit Logs Query Param** | **BE 수정**: `actor`→`actorUserId`, `action`→`actionType` alias 지원 (기존 이름 유지, `@RequestParam(required=false) Long actor`, `String action` 등으로 이중 바인딩) | BE | 하위 호환 유지 |

**P0 산출물**  
- FE: 11개 API HTTP Method를 BE와 일치시킨 배포.  
- BE: `AdminAuditLogController` + `AuditLogQueryService`에 `actor`/`action` alias 및 Service 인자 전달.

---

### 3.2. P1 (높음) — BE 주도

| ID | 항목 | 조치 | 담당 | 비고 |
|----|------|------|------|------|
| **P1-1** | **Codes Groups 필터** | `GET /api/admin/codes/groups`에 `keyword`, `tenantScope`, `enabled` 추가. `CodeManagementService.getAllGroups` 시그니처 확장 또는 오버로드. | BE | Additive, breaking 없음 |
| **P1-2** | **Code Usages List 필터** | `GET /api/admin/code-usages`에 `codeGroupKey` 추가. `CodeUsageService.getCodeUsages` 및 Repository/Query 확장. | BE | Additive |
| **P1-3** | **Resources List `resourceType`** | `GET /api/admin/resources`에서 `type`에 더해 `resourceType` alias (`@RequestParam(required=false) String resourceType` → `type == null ? resourceType : type`) | BE | Additive |
| **P1-4** | **Audit Logs Detail API** | `GET /api/admin/audit-logs/{id}` 신규. `AuditLogDetail` DTO (기존 `AuditLogItem` + `ipAddress`, `userAgent`, `beforeValue`, `afterValue` 등, `metadata_json` 파싱). `com_audit_logs`에 ip/user_agent 등 없으면 `metadata_json`에서 추출. | BE | Entity에 ip/user_agent 없으면 metadata만 사용 |
| **P1-5** | **Code Usages Detail** | `GET /api/admin/code-usages/{id}` 신규. `CodeUsageDetail` DTO (`CodeUsageSummary` + `createdBy`, `updatedBy`). `CodeUsage` 엔티티/BaseEntity에 `created_by`/`updated_by` 있으면 매핑. | BE | `CodeUsageController`에 `@GetMapping("/{id}")` 추가 |
| **P1-6** | **Resources Detail API** | `GET /api/admin/resources/{id}` 신규. `ResourceSummary` 재사용 또는 동일 구조. `ResourceManagementService` + `ResourceQueryService`에 `getById` 추가. | BE | FE Gap에는 "존재"로 되어 있었으나 BE에 없음 |
| **P1-7** | **RBAC (Menus, Audit, resources/tree)** | `AdminEndpointPolicyRegistry`에 등록: `GET /api/admin/menus`, `GET /api/admin/menus/tree`, `POST /api/admin/menus`, `PATCH /api/admin/menus/\d+`, `DELETE /api/admin/menus/\d+`, `PUT /api/admin/menus/reorder` → `menu.admin.menus` (VIEW/EDIT/EXECUTE). `GET /api/admin/audit-logs`, `GET /api/admin/audit-logs/\d+`, `POST /api/admin/audit-logs/export` → `menu.admin.audit` (VIEW). `GET /api/admin/resources/tree` → `menu.admin.resources` VIEW. | BE | `AdminGuardInterceptor`와 연동됨 |
| **P1-8** | **응답 필드 보완** | (1) **ResourceSummary**: `icon`, `status`, `description`. Resource 엔티티에 `icon`/`description` 없으면 `metadataJson`에서 추출 또는 신규 컬럼 검토. `status`는 `enabled`→ACTIVE/INACTIVE 매핑. (2) **MenuNode**: `id`(sysMenuId), `parentId`, `enabled`(isEnabled), `permissionKey`(menuKey 또는 연동 리소스 키). (3) **UserSummary/RoleDetail**: `lastLoginAt`, `updatedAt` — DTO/엔티티 확인 후 없으면 추가. | BE | 엔티티/DB 스키마 변경 필요 시 Flyway |
| **P1-9** | **Audit Export GET** | (A) `GET /api/admin/audit-logs/export?from=&to=&actor=&action=&...` 추가하여 기존 export 로직 공유, 또는 (B) FE를 `POST /export` + body 유지. 권장: (A)로 GET 추가, POST는 deprecated 또는 유지. | BE (+ FE 협의) | FE가 GET만 쓸 경우 (A) 필요 |

---

### 3.3. P2 (중간)

| ID | 항목 | 조치 | 담당 | 비고 |
|----|------|------|------|------|
| **P2-1** | **Roles Delete** | `POST /delete` 없음. FE가 `DELETE` 사용하도록 수정 시 **추가 작업 없음**. FE가 POST/delete를 고수하면 BE에 `POST /{id}/delete` 추가 가능(비권장). | FE 또는 BE | N/A 권장 |
| **P2-2** | **Code Usages GET /{id} 정책** | `AdminEndpointPolicyRegistry`에 `GET /api/admin/code-usages/\d+` 이미 등록. `GET /{id}` 구현 시 그대로 적용. | BE | P1-5와 동시 |

---

## 4. FE 요청 명세와의 매핑

| 명세서 항목 | 로드맵 ID | 비고 |
|-------------|-----------|------|
| P0 HTTP Method 11건 | P0-1 | FE 수정, BE 유지 |
| P0 Query Param (actor, action) | P0-2 | BE alias |
| P1 Codes Groups 필터 | P1-1 | |
| P1 Code Usages 필터 (codeGroupKey) | P1-2 | |
| P1 Resources resourceType | P1-3 | |
| P1 Audit Detail API | P1-4 | |
| P1 Code Usages Detail | P1-5 | |
| P1 RBAC 5개 메뉴 | P1-7 | Menus, Audit, resources/tree 미등록 구간 보완 |
| P1 응답 필드 4건 | P1-8 | Resource, MenuNode, User, Role |
| P1 Audit Export GET | P1-9 | 명세서에는 P1 |
| P2 Roles Delete 중복 | P2-1 | BE에 POST/delete 없음 → N/A |
| (보정) Resources Detail | P1-6 | FE Gap에 "존재"였으나 BE 미구현 |

---

## 5. ADMIN_REMAINING_WORK 연동

`ADMIN_REMAINING_WORK.md`는 **API 보완 완료 후** 운영·인프라·테스트·CI 쪽이다. 로드맵에서는 "Phase 2"로 두고, P0·P1·P2 종료 후 진행하는 것이 맞다.

| 영역 | 대표 항목 | 로드맵 반영 |
|------|-----------|-------------|
| 데이터 시딩/권한 | 기본 테넌트, admin, 역할, 메뉴, 코드, 코드사용 | Phase 2 (P0~P2 완료 후) |
| 로그/감사 | 보관 기간, 아카이빙 | Phase 2 |
| 성능 | 인덱스, 쿼리, N+1 | Phase 2, 성능 이슈 시 우선 |
| 배치/스케줄러 | 감사 로그 정리, 통계 | Phase 2 |
| 캐시(Redis) | 메뉴 트리, 코드 그룹, 권한 | Phase 2 |
| 장애 대응 | 타임아웃, 서킷브레이커 | Phase 2 |
| FE E2E·BE 통합 | 플로우·권한 시나리오 | P1-7 RBAC 후 권장 |
| CI/CD | Merge blocking, 커버리지 | Phase 2 |
| 운영 배포 | DB 마이그레이션, env, 모니터링 | Phase 2, 배포 전 필수 |

---

## 6. 권장 일정 (안)

| Phase | 기간 | 내용 |
|-------|------|------|
| **P0** | 1~2일 | P0-1 (FE 11건 수정), P0-2 (BE alias). 동시 진행 가능. |
| **P1** | 5~7일 | P1-1~P1-9. P1-1,2,3,7,8는 병렬 가능. P1-4,5,6,9는 각 1일 내외. |
| **P2** | 0일 | P2-1 N/A 시 불필요. |
| **Phase 2** | 별도 | `ADMIN_REMAINING_WORK.md` 일정에 따름. |

---

## 7. 체크리스트 (DoD)

- [ ] P0: Audit `actor`/`action` alias 적용, FE 11건 HTTP Method 반영 후 Audit/Resources/Menus/Codes/Code Usages 호출 정상.
- [ ] P1: Codes groups 필터, Code usages 필터+Detail, Resources resourceType+Detail, Audit Detail+Export GET(선택), RBAC Menus/Audit/resources/tree, 응답 필드(Resource, MenuNode, User, Role) 보완.
- [ ] `AdminEndpointPolicyRegistry`에 Menus, Audit, resources/tree 등록 및 `AdminGuardInterceptor` 동작 확인.
- [ ] `AuthSmokeIT` 또는 기존 Admin IT에 Audit list(alias), Code usages list(codeGroupKey), Resources list(resourceType), Audit Detail, Code Usages Detail, Resources Detail (선택) 검증 추가.
- [ ] OpenAPI/`springdoc` 및 `ADMIN_API_GAP_ANALYSIS.md` 갱신.

---

## 8. 참고 — BE 현재 상태 (요약)

- **Users, Roles**: `PermissionEvaluator` + `AdminEndpointPolicyRegistry` 적용. Users `disable`=PUT, `delete`=POST(`/status`, `DELETE /{id}` 등 혼재 가능성, FE 명세와 대조 권장).
- **Resources**: `GET /tree`, `GET`(list), `POST`, `PUT /{id}`, `DELETE /{id}`. `GET /{id}` 없음. `type`만 있음. `AdminEndpointPolicyRegistry` 등록 O. `ResourceSummary`에 `icon`/`status`/`description` 없음.
- **Menus**: `GET`, `GET /tree`, `POST`, `PATCH /{id}`, `DELETE /{id}`, `PUT /reorder`. `AdminEndpointPolicyRegistry`에 **미등록**. `MenuNode`에 `id`/`parentId`/`enabled`/`permissionKey` 보완 여지.
- **Codes**: `GET /groups`(파라미터 없음), `GET`, `GET /all`, `GET /usage`, `GET /usage/groups`, `POST`/`PUT`/`DELETE` groups 및 codes. `AdminEndpointPolicyRegistry` 등록 O.
- **Code Usages**: `GET`(list), `POST`, `PATCH /{id}`, `DELETE /{id}`. `GET /{id}` **없음**. `codeGroupKey` 미지원. Registry에는 `GET /code-usages/\d+` 등록.
- **Audit Logs**: `GET`(list, `actorUserId`/`actionType`), `POST /export`. `GET /{id}` 없음. Registry **미등록**.

---

**다음 단계**:  
1) P0 FE/BE 협의 및 일정 확정.  
2) P0-2 BE 구현 후, P1 순차/병렬 진행.  
3) Phase 2는 `ADMIN_REMAINING_WORK.md`와 연동하여 스프린트에 반영.
