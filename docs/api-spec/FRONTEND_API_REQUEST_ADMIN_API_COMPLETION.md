# Admin API 보완 요청 명세서 (FE → BE)

> **작성일**: 2026-01-22  
> **요청자**: Frontend Team  
> **우선순위**: P0 (긴급) → P1 (높음) → P2 (중간)  
> **관련 문서**: `docs/specs/admin/ADMIN_API_GAP_ANALYSIS.md`

---

## 📋 목차

1. [P0 (긴급) - HTTP Method 불일치 수정](#p0-긴급---http-method-불일치-수정)
2. [P0 (긴급) - Query Param 이름 불일치 수정](#p0-긴급---query-param-이름-불일치-수정)
3. [P1 (높음) - 필터/정렬 기능 추가](#p1-높음---필터정렬-기능-추가)
4. [P1 (높음) - Detail API 추가](#p1-높음---detail-api-추가)
5. [P1 (높음) - RBAC 권한 체크 추가](#p1-높음---rbac-권한-체크-추가)
6. [P1 (높음) - 응답 필드 보완](#p1-높음---응답-필드-보완)
7. [P2 (중간) - 코드 정리](#p2-중간---코드-정리)
8. [테스트 요구사항](#테스트-요구사항)
9. [남은 작업 백로그](#남은-작업-백로그)

---

## P0 (긴급) - HTTP Method 불일치 수정

### 요청 배경

FE는 일부 API를 `POST` 메서드로 호출하지만, BE는 REST 표준(`PUT`, `PATCH`, `DELETE`)을 사용하고 있어 **호출 실패**가 발생합니다.

### 영향도

**높음**: 화면에서 수정/삭제 기능이 동작하지 않음

### 수정 대상 API

| 메뉴 | API | FE 호출 | BE 현재 | 수정 방향 | 우선순위 |
|------|-----|---------|---------|-----------|----------|
| **Resources** | Update | `POST /api/admin/resources/:id` | `PUT /api/admin/resources/:id` | BE 표준 유지, FE 수정 필요 | P0 |
| | Delete | `POST /api/admin/resources/:id/delete` | `DELETE /api/admin/resources/:id` | BE 표준 유지, FE 수정 필요 | P0 |
| **Menus** | Update | `PUT /api/admin/menus/:id` | `PATCH /api/admin/menus/:id` | BE 표준 유지, FE 수정 필요 | P0 |
| | Delete | `POST /api/admin/menus/:id/delete` | `DELETE /api/admin/menus/:id` | BE 표준 유지, FE 수정 필요 | P0 |
| | Reorder | `POST /api/admin/menus/reorder` | `PUT /api/admin/menus/reorder` | BE 표준 유지, FE 수정 필요 | P0 |
| **Codes** | Groups Update | `POST /api/admin/codes/groups/:id` | `PUT /api/admin/codes/groups/:id` | BE 표준 유지, FE 수정 필요 | P0 |
| | Groups Delete | `POST /api/admin/codes/groups/:id/delete` | `DELETE /api/admin/codes/groups/:id` | BE 표준 유지, FE 수정 필요 | P0 |
| | Codes Update | `POST /api/admin/codes/:id` | `PUT /api/admin/codes/:id` | BE 표준 유지, FE 수정 필요 | P0 |
| | Codes Delete | `POST /api/admin/codes/:id/delete` | `DELETE /api/admin/codes/:id` | BE 표준 유지, FE 수정 필요 | P0 |
| **Code Usages** | Update | `POST /api/admin/code-usages/:id` | `PATCH /api/admin/code-usages/:id` | BE 표준 유지, FE 수정 필요 | P0 |
| | Delete | `POST /api/admin/code-usages/:id/delete` | `DELETE /api/admin/code-usages/:id` | BE 표준 유지, FE 수정 필요 | P0 |

### 해결 방안

**옵션 A: BE 표준 유지 (권장)**
- BE는 REST 표준(`PUT`, `PATCH`, `DELETE`) 유지
- FE 코드 수정 필요
- **장점**: REST 표준 준수, 일관성 유지
- **단점**: FE 코드 수정 필요

**옵션 B: BE를 FE에 맞춤 (비권장)**
- BE가 `POST` 메서드도 지원하도록 수정
- **장점**: FE 코드 수정 불필요
- **단점**: REST 표준 위반, 중복 엔드포인트

### 권장 사항

**BE 표준 유지 (옵션 A)**를 권장합니다. FE 코드 수정이 필요하지만, REST 표준 준수와 장기 유지보수 측면에서 유리합니다.

### 작업 범위

1. FE 코드 수정 (11개 API)
   - `libs/shared-utils/src/api/admin-iam-api.ts`
   - `libs/shared-utils/src/api/code-usage-api.ts`
2. 테스트 수정
   - E2E 테스트 (필요 시)

### 예상 완료일

**2026-01-25** (FE 수정 완료)

---

## P0 (긴급) - Query Param 이름 불일치 수정

### 요청 배경

FE는 `actor`, `action` query param을 사용하지만, BE는 `actorUserId`, `actionType`을 사용하여 **필터가 동작하지 않습니다**.

### 영향도

**높음**: Audit Logs 화면에서 필터 기능이 동작하지 않음

### 수정 대상 API

| API | FE 요구 | BE 현재 | 수정 방향 | 우선순위 |
|-----|---------|---------|-----------|----------|
| **Audit Logs List** | `actor` | `actorUserId` | BE 수정 또는 FE 수정 | P0 |
| | `action` | `actionType` | BE 수정 또는 FE 수정 | P0 |

### 해결 방안

**옵션 A: BE 수정 (권장)**
- BE가 `actor`, `action`도 지원하도록 수정 (기존 `actorUserId`, `actionType`은 deprecated)
- **장점**: FE 코드 수정 불필요, 표준화
- **단점**: BE 코드 수정 필요

**옵션 B: FE 수정**
- FE가 `actorUserId`, `actionType`을 사용하도록 수정
- **장점**: BE 코드 수정 불필요
- **단점**: FE 코드 수정 필요

### 권장 사항

**BE 수정 (옵션 A)**을 권장합니다. `actor`, `action`이 더 간결하고 직관적입니다.

### 작업 범위

1. BE Controller 수정
   - `AdminAuditLogController.java`
   - `@RequestParam` 이름 변경 또는 둘 다 지원
2. BE Service 수정
   - `AuditLogQueryService.java`
3. 테스트 수정
   - Controller 테스트

### 예상 완료일

**2026-01-25**

---

## P1 (높음) - 필터/정렬 기능 추가

### 요청 배경

일부 API에서 필터/정렬 기능이 부족하여 운영 시 사용성이 떨어집니다.

### 1. Codes Groups List 필터 추가

**API**: `GET /api/admin/codes/groups`

**현재 상태**: 필터 없음

**요구사항**:
- `keyword` (groupKey, groupName 검색)
- `tenantScope` (`COMMON`, `TENANT`, `ALL`)
- `enabled` (`true`, `false`)

**Request 예시**:
```
GET /api/admin/codes/groups?keyword=USER&tenantScope=ALL&enabled=true
```

**Response**: 기존과 동일 (`List<CodeGroupResponse>`)

**우선순위**: P1

**예상 완료일**: 2026-01-30

---

### 2. Code Usages List 필터 추가

**API**: `GET /api/admin/code-usages`

**현재 상태**: `resourceKey`, `keyword`, `enabled`만 지원

**요구사항**:
- `codeGroupKey` 추가 (코드 그룹별 필터)

**Request 예시**:
```
GET /api/admin/code-usages?codeGroupKey=USER_STATUS&enabled=true
```

**Response**: 기존과 동일 (`PageResponse<CodeUsageSummary>`)

**우선순위**: P1

**예상 완료일**: 2026-01-30

---

### 3. Resources List 필터 이름 통일

**API**: `GET /api/admin/resources`

**현재 상태**: `type` query param 사용

**요구사항**:
- FE는 `resourceType`을 기대하지만, BE는 `type` 사용
- `resourceType`도 지원하도록 수정 (또는 FE 수정)

**Request 예시**:
```
GET /api/admin/resources?resourceType=MENU
```

**해결 방안**: BE가 `resourceType`도 지원하도록 수정 (기존 `type`은 deprecated)

**우선순위**: P1

**예상 완료일**: 2026-01-30

---

## P1 (높음) - Detail API 추가

### 1. Audit Logs Detail API 추가

**API**: `GET /api/admin/audit-logs/:id`

**현재 상태**: 미존재

**요구사항**:
- 감사 로그 상세 정보 조회
- Summary보다 더 많은 필드 포함 (변경 전/후 값, IP 주소 등)

**Request 예시**:
```
GET /api/admin/audit-logs/123
```

**Response 예시**:
```json
{
  "status": "SUCCESS",
  "success": true,
  "data": {
    "id": "123",
    "actorUserId": "1",
    "actorName": "admin",
    "action": "USER_CREATE",
    "resourceType": "USER",
    "resourceId": "456",
    "resourceName": "testuser",
    "timestamp": "2026-01-22T10:00:00",
    "details": "사용자 생성",
    "ipAddress": "192.168.1.1",
    "userAgent": "Mozilla/5.0...",
    "beforeValue": null,
    "afterValue": "{ \"userName\": \"testuser\" }"
  }
}
```

**DTO 추가 필요**:
- `AuditLogDetail.java` (기존 `AuditLogItem` 확장 또는 별도)

**우선순위**: P1

**예상 완료일**: 2026-01-30

---

### 2. Code Usages Detail DTO 보완

**API**: `GET /api/admin/code-usages/:id`

**현재 상태**: `CodeUsageSummary`만 반환

**요구사항**:
- `CodeUsageDetail` DTO 추가 (Summary보다 더 많은 필드)
- 또는 Summary에 필요한 필드 추가

**Request 예시**:
```
GET /api/admin/code-usages/123
```

**Response 예시**:
```json
{
  "status": "SUCCESS",
  "success": true,
  "data": {
    "id": "123",
    "resourceKey": "menu.admin.users",
    "codeGroupKey": "USER_STATUS",
    "enabled": true,
    "createdAt": "2026-01-22T10:00:00",
    "updatedAt": "2026-01-22T10:00:00",
    "createdBy": "admin",
    "updatedBy": "admin"
  }
}
```

**DTO 추가 필요**:
- `CodeUsageDetail.java` (기존 `CodeUsageSummary` 확장)

**우선순위**: P1

**예상 완료일**: 2026-01-30

---

## P1 (높음) - RBAC 권한 체크 추가

### 요청 배경

일부 Admin API에 권한 체크가 없어 보안 위험이 있습니다.

### 수정 대상 API

| 메뉴 | API | 현재 상태 | 요구사항 |
|------|-----|-----------|----------|
| **Resources** | 모든 API | 권한 체크 없음 | `menu.admin.resources` + `VIEW`/`EDIT` 권한 체크 추가 |
| **Menus** | 모든 API | 권한 체크 없음 | `menu.admin.menus` + `VIEW`/`EDIT` 권한 체크 추가 |
| **Codes** | 모든 API | 권한 체크 없음 | `menu.admin.codes` + `VIEW`/`EDIT` 권한 체크 추가 |
| **Code Usages** | 모든 API | 권한 체크 없음 | `menu.admin.code-usages` + `VIEW`/`EDIT` 권한 체크 추가 |
| **Audit Logs** | 모든 API | 권한 체크 없음 | `menu.admin.audit` + `VIEW` 권한 체크 추가 |

### 구현 방식

**기존 패턴 참고**: `UserController`, `RoleController`의 `PermissionEvaluator` 사용

**예시**:
```java
@GetMapping
public ApiResponse<PageResponse<ResourceSummary>> getResources(
        @RequestHeader("X-Tenant-ID") Long tenantId,
        Authentication authentication,
        ...) {
    Long userId = getUserId(authentication);
    permissionEvaluator.requirePermission(userId, tenantId, "menu.admin.resources", "VIEW");
    return ApiResponse.success(...);
}
```

### 권한 매핑

| 메뉴 | Resource Key | View 권한 | Edit 권한 |
|------|--------------|-----------|-----------|
| **Resources** | `menu.admin.resources` | GET (List, Tree, Detail) | POST, PUT, DELETE |
| **Menus** | `menu.admin.menus` | GET (List, Tree) | POST, PATCH, DELETE, PUT (Reorder) |
| **Codes** | `menu.admin.codes` | GET (Groups, Codes, Usage) | POST, PUT, DELETE |
| **Code Usages** | `menu.admin.code-usages` | GET (List, Detail) | POST, PATCH, DELETE |
| **Audit Logs** | `menu.admin.audit` | GET (List, Detail, Export) | - (조회 전용) |

### 우선순위

**P1** (보안 이슈)

### 예상 완료일

**2026-01-30**

---

## P1 (높음) - 응답 필드 보완

### 요청 배경

일부 응답 DTO에 FE가 기대하는 필드가 누락되어 있습니다.

### 1. Resources API 응답 필드 보완

**API**: `GET /api/admin/resources`, `GET /api/admin/resources/:id`

**현재 상태**: 확인 필요

**요구 필드**:
- `icon?: string | null` (아이콘 경로/이름)
- `status: 'ACTIVE' | 'INACTIVE'` (활성 상태)
- `description?: string | null` (설명)

**DTO 수정 필요**:
- `ResourceSummary.java`

**우선순위**: P1

**예상 완료일**: 2026-01-30

---

### 2. Menus API 응답 필드 보완

**API**: `GET /api/admin/menus/tree`

**현재 상태**: 확인 필요

**요구 필드**:
- `sortOrder?: number | null` (정렬 순서)
- `permissionKey?: string | null` (권한 키)

**DTO 수정 필요**:
- `MenuNode.java` 또는 `MenuSummary.java`

**우선순위**: P1

**예상 완료일**: 2026-01-30

---

### 3. Users API 응답 필드 확인

**API**: `GET /api/admin/users`, `GET /api/admin/users/:id`

**요구 필드**:
- `lastLoginAt?: string | null` (마지막 로그인 시간)

**확인 필요**: BE DTO에 포함되어 있는지 확인

**우선순위**: P1 (확인 후 결정)

---

### 4. Roles API 응답 필드 확인

**API**: `GET /api/admin/roles/:id`

**요구 필드**:
- `updatedAt?: string | null` (수정 시간)

**확인 필요**: BE DTO에 포함되어 있는지 확인

**우선순위**: P1 (확인 후 결정)

---

## P2 (중간) - 코드 정리

### 1. Roles Delete 중복 제거

**현재 상태**: `DELETE`와 `POST /delete` 둘 다 존재

**요구사항**:
- REST 표준에 맞게 `DELETE`만 유지
- `POST /delete` 제거 또는 deprecated 처리

**우선순위**: P2

**예상 완료일**: 2026-02-05

---

## 테스트 요구사항

### 최소 테스트 케이스

각 API별로 최소 1개 이상의 테스트가 필요합니다.

**테스트 프레임워크**: JUnit 5 + MockMvc (또는 Testcontainers)

**테스트 범위**:
1. **성공 케이스**: 정상 요청/응답
2. **실패 케이스**: 권한 없음 (403), 리소스 없음 (404), 검증 실패 (400)
3. **엣지 케이스**: 빈 리스트, 페이징 경계값

**예시 테스트 구조**:
```java
@AutoConfigureMockMvc
class ResourceControllerTest extends TestcontainersBase {
    @Test
    @DisplayName("리소스 목록 조회 성공")
    void testGetResources() throws Exception {
        mockMvc.perform(get("/api/admin/resources")
                .header("X-Tenant-ID", "1")
                .param("page", "1")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray());
    }
    
    @Test
    @DisplayName("리소스 목록 조회 - 권한 없음 (403)")
    void testGetResources_Forbidden() throws Exception {
        // 권한 없는 사용자로 요청
        // 403 응답 확인
    }
}
```

**우선순위**: P1 (기능 추가 시 필수)

---

## 남은 작업 백로그

> **문서**: `docs/specs/admin/ADMIN_REMAINING_WORK.md` 참고

### 운영 준비 작업

1. **데이터 시딩/샘플 계정/권한 세트**
   - 개발/스테이징 환경 초기 데이터
   - 테스트 계정 및 권한 설정

2. **운영 로그/감사로그 적재 정책**
   - 로그 보관 기간
   - 로그 압축/아카이빙 전략

3. **대량 데이터 성능 최적화**
   - 인덱스 추가 (필요 시)
   - 쿼리 최적화

4. **배치/스케줄러 필요 여부**
   - 정기 데이터 정리
   - 통계 집계

5. **캐시 전략 (Redis)**
   - 메뉴 트리 캐싱
   - 코드 그룹 캐싱

6. **장애 대응**
   - 타임아웃 설정
   - 서킷브레이커 적용

### 통합 테스트

1. **FE E2E와 BE 통합 테스트**
   - 전체 플로우 테스트
   - 권한 시나리오 테스트

2. **CI/CD Merge blocking 전략**
   - 테스트 통과 필수
   - 코드 커버리지 기준

3. **운영 배포 체크리스트**
   - DB 마이그레이션
   - 환경 변수 설정
   - 모니터링 설정

---

## 승인 및 진행 상황

### 승인 상태

- [ ] Tech Lead 승인
- [ ] 백엔드 팀 승인
- [ ] 일정 확정

### 진행 상황

| 항목 | 상태 | 완료일 | 비고 |
|------|------|--------|------|
| P0 - HTTP Method 불일치 | ✅ FE 완료 | 2026-01-23 | FE: axiosInstance patch/delete 추가, admin-iam-api 9건, code-usage-api 2건 수정 |
| P0 - Query Param 불일치 | ⏳ 대기 | - | BE 수정 필요 |
| P1 - 필터/정렬 추가 | ⏳ 대기 | - | - |
| P1 - Detail API 추가 | ⏳ 대기 | - | - |
| P1 - RBAC 권한 체크 | ⏳ 대기 | - | - |
| P1 - 응답 필드 보완 | ⏳ 대기 | - | - |
| P2 - 코드 정리 | ⏳ 대기 | - | - |

---

## 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|-----------|--------|
| 2026-01-22 | 초안 작성 | Frontend Team |

---

**다음 단계**: 백엔드 팀과 논의 후 우선순위 확정 및 일정 조율
