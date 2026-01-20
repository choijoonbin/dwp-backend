# Service 리팩토링 가이드

## 목적

DWP Backend의 Service/Controller가 비대해지는 것을 방지하고, 운영 수준의 구조로 개선하기 위한 가이드입니다.

## 구조 규칙

### 1. CQRS 패턴 (Query/Command 분리)

#### Query Service
- **책임**: 조회 전용
- **트랜잭션**: `@Transactional(readOnly = true)` 필수
- **예시**: `RoleQueryService`, `UserQueryService`

```java
@Service
@Transactional(readOnly = true)
public class RoleQueryService {
    public PageResponse<RoleSummary> getRoles(Long tenantId, int page, int size, String keyword) {
        // 조회 로직만
    }
}
```

#### Command Service
- **책임**: 생성/수정/삭제 전용
- **트랜잭션**: `@Transactional` 필수
- **예시**: `RoleCommandService`, `UserCommandService`

```java
@Service
@Transactional
public class RoleCommandService {
    public RoleDetail createRole(Long tenantId, Long actorUserId, CreateRoleRequest request) {
        // 변경 로직만
    }
}
```

### 2. Validator 레이어 분리

#### 책임
- tenantId, resourceKey, subjectType, loginType, action normalize 규칙 검증
- require/validate 계열 표준화 (예외 코드 통일)

#### 예시
```java
@Component
public class UserValidator {
    public void validateTenantId(Long tenantId) {
        if (tenantId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-Tenant-ID 헤더가 필요합니다");
        }
    }
    
    public void validateEmailNotDuplicate(Long tenantId, String email) {
        // 중복 체크 로직
    }
}
```

#### 사용 규칙
- Service에서 Validator를 주입받아 사용
- 검증 실패 시 `BaseException` 발생 (예외 코드 통일)

### 3. Mapper 레이어 분리

#### 책임
- Entity ↔ DTO 변환만 담당
- 컨트롤러/서비스에서 DTO 생성하는 코드 제거

#### 예시
```java
@Component
public class UserMapper {
    public UserSummary toUserSummary(Long tenantId, User user, String loginId, String departmentName) {
        return UserSummary.builder()
                .comUserId(user.getUserId())
                .tenantId(tenantId)
                // ... 매핑 로직
                .build();
    }
}
```

#### 사용 규칙
- Service에서 Mapper를 주입받아 사용
- Entity → DTO 변환은 Mapper에서만 수행
- Service 내부에서 builder로 DTO 조립 로직이 200라인 넘어가는 형태 금지

## 패키지 구조

```
com.dwp.services.auth.service/
├─ admin/
│  ├─ UserManagementService.java      (Facade)
│  └─ user/                           (신규)
│     ├─ UserQueryService.java        (조회 전용)
│     ├─ UserCommandService.java      (변경 전용)
│     ├─ UserValidator.java           (검증)
│     └─ UserMapper.java              (변환)
├─ role/
│  ├─ RoleQueryService.java           (조회 전용)
│  └─ RoleCommandService.java         (변경 전용)
├─ rbac/
│  ├─ AdminGuardService.java          (캐싱, ADMIN 검증)
│  └─ PermissionCalculator.java       (권한 계산 핵심)
└─ monitoring/
   ├─ MonitoringCollectService.java   (수집)
   └─ MonitoringValidator.java         (검증)
```

## 트랜잭션 규칙

### Query Service
- `@Transactional(readOnly = true)` 필수
- 조회 전용이므로 읽기 전용 트랜잭션 사용

### Command Service
- `@Transactional` 필수
- 생성/수정/삭제 작업이므로 쓰기 트랜잭션 사용

### Controller
- `@Transactional` 금지
- Controller는 Service에 위임만 수행

## 멀티테넌시 규칙

### tenant_id 필터 강제
- 모든 Repository 호출에 `tenantId` 파라미터 필수
- Query 메서드명에 `ByTenantId` 포함 강제
- 다른 tenantId로 접근 시 403/404 반환

### 예시
```java
// ✅ 올바른 예
userRepository.findByTenantIdAndUserId(tenantId, userId);

// ❌ 잘못된 예
userRepository.findByUserId(userId);  // tenantId 누락
```

## 캐시 정책

### 캐시 Key 규칙
- `CodeResolver`: `groupKey` (예: "RESOURCE_TYPE", "UI_ACTION")
- `CodeUsageService`: `tenantId + ":" + resourceKey` (예: "1:menu.admin.users")

### 캐시 무효화
- CRUD 발생 시 캐시 무효화 단일 함수로 통일
- `CodeResolver.clearCache()`: 전체 캐시 초기화
- `CodeUsageService.clearCache(tenantId, resourceKey)`: 특정 키만 제거

## 클래스 크기 제한

### Hard Limit
- Controller: 250라인 초과 금지
- Service: 350라인 초과 금지
- Repository/Entity: 300라인 초과 금지

### 초과 시 조치
- 책임 단위로 분리 (Query/Command/Validator/Mapper)
- Facade 패턴으로 기존 API 호환성 유지

## 테스트 규칙

### 필수 테스트
- QueryService: 조회 로직 테스트
- CommandService: 변경 로직 테스트
- Validator: 검증 로직 테스트
- PermissionCalculator: 권한 계산 로직 테스트

### 테스트 예시
```java
@Test
void testPermissionCalculator_DenyFirst() {
    // DENY 우선 정책 검증
}

@Test
void testCodeUsageCacheInvalidation() {
    // usage CRUD 후 캐시 무효화 검증
}
```
