# [DWP Backend] Admin CRUD 운영급 표준 템플릿

## 목표
- Admin CRUD API는 확장해도 Service/Controller가 비대해지지 않도록 구조를 표준화한다.
- tenant_id 격리, ApiResponse<T>, CodeResolver/CodeUsage 기반 검증, Audit 기록을 기본값으로 제공한다.
- FK 제약 없이도 운영 가능하도록 논리적 참조 + validation을 강화한다.

---

## 1) 패키지 구조 (필수)

```
com.dwp.services.auth
├── controller/admin/
│   └── Admin<Feature>Controller.java
│
├── service/admin/<feature>/
│   ├── <Feature>QueryService.java      # list/detail 조회
│   ├── <Feature>CommandService.java    # create/update/delete
│   └── <Feature>Validator.java          # 입력/참조 검증
│
├── dto/admin/<feature>/
│   ├── <Feature>ListRequest.java        # (선택, 복잡한 필터일 때)
│   ├── <Feature>Summary.java            # 목록 항목 DTO
│   ├── <Feature>Detail.java             # 상세 DTO
│   ├── Create<Feature>Request.java
│   └── Update<Feature>Request.java
│
├── repository/
│   ├── <Feature>Repository.java
│   └── <Feature>RepositoryCustom.java   # 검색/필터 복잡할 때
│
└── entity/
    └── <Feature>.java
```

### 예시: Users
```
controller/admin/
└── UserController.java

service/admin/user/
├── UserQueryService.java
├── UserCommandService.java
└── UserValidator.java

dto/admin/user/
├── UserSummary.java
├── UserDetail.java
├── CreateUserRequest.java
└── UpdateUserRequest.java
```

### 규칙
- ✅ Controller는 "validation + service 호출"만
- ✅ Query/Command는 분리 (거대 서비스 방지)
- ✅ Validator는 입력/참조 검증 전담

---

## 2) API 규칙 (통일)

### List
```
GET /api/admin/<feature>
Query Parameters:
  - page (default: 1)
  - size (default: 20)
  - keyword (optional)
  - filters... (optional)
```

### Detail
```
GET /api/admin/<feature>/{id}
```

### Create
```
POST /api/admin/<feature>
Body: Create<Feature>Request
```

### Update
```
PATCH /api/admin/<feature>/{id}
Body: Update<Feature>Request
```

### Delete
```
DELETE /api/admin/<feature>/{id}
```

### 응답 규칙
- ✅ 모든 응답: `ApiResponse<T>`
- ✅ 모든 요청: `X-Tenant-ID` 헤더 필수
- ✅ `/api/admin/**`: JWT 필수 + ADMIN 권한 enforcement

---

## 3) 멀티테넌시/권한 Enforcement (필수)

### tenant_id 격리
- 모든 조회/수정에 `tenant_id` 강제 포함
- Repository 메서드는 `findByTenantIdAnd*` 패턴 사용
- Service에서 `tenantId` 검증 필수

### 권한 Enforcement (2단계)

#### 1) AdminGuardInterceptor
- `/api/admin/**` 엔드포인트 접근 시 ADMIN 역할 체크
- JWT에서 `tenant_id`, `user_id` 추출
- `AdminGuardService.requireAdminRole(tenantId, userId)` 호출

#### 2) Resource Permission Enforcement (선택)
- 세부 권한이 필요한 경우: `resourceKey + permissionCode` 기반 검증
- `AdminGuardService.canAccess(userId, tenantId, resourceKey, permissionCode)` 호출

### 금지 사항
- ❌ "프론트에서만 막는 구조" 금지
- ❌ Controller에서 권한 체크 생략 금지

---

## 4) CodeResolver/CodeUsage 기반 검증 (하드코딩 금지)

### 하드코딩 금지 값
다음 값은 문자열 하드코딩 금지:
- `RESOURCE_TYPE`, `RESOURCE_KIND`
- `SUBJECT_TYPE`, `UI_ACTION`
- `ROLE_CODE`, `PERMISSION_CODE`
- `LOGIN_TYPE`, `IDP_PROVIDER_TYPE`
- `USER_STATUS`, `EFFECT_TYPE`

### 검증 방식

#### CodeResolver 사용
```java
// 코드 존재 여부 검증
codeResolver.require("SUBJECT_TYPE", "USER");

// 테넌트별 코드 검증 (tenantId 포함)
codeResolver.require("SUBJECT_TYPE", "USER", tenantId);
```

#### CodeUsageService 사용
```java
// resourceKey에 매핑된 코드 목록 조회
List<String> validCodes = codeUsageService.getCodesByResourceKey(
    "menu.admin.users", tenantId);

// 유효한 코드인지 확인
if (!validCodes.contains(request.getStatus())) {
    throw new BaseException(ErrorCode.INVALID_CODE, "유효하지 않은 상태 코드입니다.");
}
```

### 테넌트별 코드 지원
- `sys_codes.tenant_id` 컬럼 지원 유지
- 테넌트별 코드가 없으면 글로벌 코드 사용

---

## 5) 감사로그/추적성 (운영 기본값)

### 감사로그 기록
모든 Admin CRUD는 `com_audit_logs` 테이블에 기록합니다.

#### 필수 필드
- `tenant_id`: 테넌트 ID
- `actor_user_id`: 작업 수행자 사용자 ID
- `action`: 작업 유형 (`CREATE`, `UPDATE`, `DELETE`)
- `target_type`: 대상 타입 (`USER`, `ROLE`, `RESOURCE` 등)
- `target_id`: 대상 ID
- `before_json`: 변경 전 데이터 (JSON)
- `after_json`: 변경 후 데이터 (JSON)
- `trace_id`: 추적 ID (가능한 경우)

#### 사용 예시
```java
@Transactional
public UserDetail createUser(Long tenantId, Long actorUserId, 
                             CreateUserRequest request,
                             HttpServletRequest httpRequest) {
    // ... 사용자 생성 로직 ...
    
    // 감사 로그 기록
    auditLogService.recordAuditLog(
        tenantId, 
        actorUserId, 
        "USER_CREATE", 
        "USER", 
        user.getUserId(),
        null,  // before (생성 시)
        user,  // after
        httpRequest
    );
    
    return userDetail;
}
```

### 실패 케이스 기록
- 실패 시에도 최소한의 감사 로그 기록 (원인 포함)
- 예: `action = "USER_CREATE_FAILED", after_json = { "error": "..." }`

---

## 6) DTO 변환/응답 설계 규칙

### Entity 직접 반환 금지
- ❌ Controller에서 Entity 직접 반환 금지
- ✅ Response DTO에만 외부 노출 필드 포함

### DTO 구조

#### List Response
```java
@Getter
@Builder
public class FeatureSummary {
    private Long id;
    private String name;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // 외부 노출 필드만 포함
}
```

#### Detail Response
```java
@Getter
@Builder
public class FeatureDetail {
    private Long id;
    private String name;
    private String status;
    private List<RelatedInfo> relatedItems;  // 관련 정보
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

#### PageResponse
```java
@Getter
@Builder
public class PageResponse<T> {
    private List<T> items;
    private int page;
    private int size;
    private long totalItems;
    private int totalPages;
}
```

### Null 처리 정책
- `String` 필드: `null` 허용 (명시적으로 `@Nullable` 표시)
- `List` 필드: `null` 대신 `Collections.emptyList()` 사용
- `LocalDateTime` 필드: `null` 허용 (선택적 필드)

### Mapper 사용
- Entity ↔ DTO 변환은 `Mapper` 클래스에서 담당
- Service에서 직접 변환 로직 작성 금지

---

## 7) Query 성능 가이드 (필수)

### List API 기본 규칙
- `WHERE tenant_id = ?` 필수
- `keyword` 검색 시 `LIKE/ILIKE` 사용
- 정렬: `updated_at DESC` 기본
- Pagination: `Pageable` 사용

### 인덱스 필요 시
- Flyway 마이그레이션으로 인덱스 추가
- FK 제약 없음 유지 (논리적 참조만)

### 복잡한 쿼리 분리
- 복잡한 검색/필터는 `RepositoryCustom` 인터페이스로 분리
- QueryDSL 또는 Native Query 사용 가능

---

## 8) 테스트 최소 기준

### ControllerTest 필수 케이스
1. **List 성공**: 페이징 + 필터
2. **Detail 성공**: ID로 조회
3. **Create 성공**: 유효한 요청
4. **Update 성공**: 유효한 요청
5. **Delete 성공**: 유효한 ID

### 추가 테스트 케이스
1. **tenant_id 격리 테스트**: 다른 tenant의 데이터 접근 불가 확인
2. **CodeResolver validate 실패**: 유효하지 않은 코드 값 거부
3. **권한 검증 실패**: ADMIN 역할 없이 접근 시 403

### 테스트 예시
```java
@WebMvcTest(UserController.class)
class UserControllerTest {
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void getUsers_WithPaging_Success() {
        // Given
        Long tenantId = 1L;
        int page = 1;
        int size = 20;
        
        // When & Then
        mockMvc.perform(get("/api/admin/users")
                .header("X-Tenant-ID", tenantId)
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void createUser_InvalidCode_ThrowsException() {
        // Given
        CreateUserRequest request = CreateUserRequest.builder()
                .status("INVALID_STATUS")  // 유효하지 않은 코드
                .build();
        
        // When & Then
        // CodeResolver에서 예외 발생 확인
    }
}
```

---

## 9) 완료 기준 (DoD)

### 코드 품질
- ✅ Controller 200라인 이하 유지
- ✅ Service 500라인 이하 유지
- ✅ Query/Command/Validator 분리 완료

### 기능 요구사항
- ✅ `ApiResponse<T>` 유지
- ✅ `tenant_id` 필터링 누락 0건
- ✅ 하드코딩 0건 (CodeResolver/CodeUsage로 대체)
- ✅ 감사로그 기록 확인

### 테스트
- ✅ 테스트 통과
- ✅ tenant_id 격리 테스트 포함
- ✅ CodeResolver validate 실패 케이스 포함

---

## 10) 구현 체크리스트

새로운 Admin CRUD 기능 추가 시:

- [ ] 패키지 구조 준수 (`controller/admin/`, `service/admin/<feature>/`)
- [ ] Query/Command 분리
- [ ] Validator 클래스 생성
- [ ] DTO 클래스 생성 (Summary, Detail, Request)
- [ ] Controller는 validation + service 호출만
- [ ] `tenant_id` 필터링 모든 조회/수정에 포함
- [ ] CodeResolver/CodeUsage 기반 검증 (하드코딩 없음)
- [ ] 감사로그 기록 (`AuditLogService` 사용)
- [ ] `ApiResponse<T>` 응답
- [ ] 테스트 작성 (최소 기준 충족)

---

## 11) 기존 코드와의 호환성

### 현재 상태
- ✅ `UserController`, `RoleController` 등 이미 표준 구조 준수
- ✅ `UserManagementService`는 Query/Command 분리 완료
- ✅ `RoleManagementService`는 Query/Command 분리 완료
- ✅ `AdminGuardService`는 권한 검증 전담

### 개선 필요 사항
- 일부 Controller가 200라인 초과 시 분리 고려
- 일부 Service가 500라인 초과 시 추가 분리 고려
- 모든 하드코딩을 CodeResolver/CodeUsage로 대체

---

## 12) 참고 문서

- [SERVICE_REFACTOR_GUIDE.md](./SERVICE_REFACTOR_GUIDE.md): Service 분리 가이드
- [RBAC_CALCULATION_POLICY.md](./RBAC_CALCULATION_POLICY.md): RBAC 정책
- [BE_REFACTOR_STEP2_COMPLETE.md](../workdone/BE_REFACTOR_STEP2_COMPLETE.md): 리팩토링 완료 보고서
