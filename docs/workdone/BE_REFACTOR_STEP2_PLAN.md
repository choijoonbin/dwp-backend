# [DWP Backend] BE Refactor Step-2: 구조 안정화 계획서

## 0) 리팩토링 대상 TOP 3 선정

### 라인 수 분석 결과

| 순위 | 클래스명 | 라인 수 | 위치 | 분해 필요성 |
|------|---------|---------|------|------------|
| 1 | UserManagementService | 643 | service/admin/ | ⚠️ 높음 (500라인 초과) |
| 2 | RoleCommandService | 391 | service/admin/role/ | ⚠️ 중간 (300라인 이상) |
| 3 | AdminGuardService | 352 | service/rbac/ | ⚠️ 중간 (300라인 이상) |

### TOP 3 상세 분석

#### 1. UserManagementService (643 라인)

**현재 책임**:
- 사용자 목록 조회 (페이징, 검색, 필터)
- 사용자 생성
- 사용자 상세 조회
- 사용자 수정
- 사용자 상태 변경
- 사용자 삭제
- 비밀번호 재설정
- 사용자 역할 조회/추가/삭제/업데이트
- DTO 변환 (toUserSummary, copyUser)

**분해 방안**:
- `UserQueryService`: 조회 전용 (getUsers, getUserDetail, getUserRoles)
- `UserCommandService`: 생성/수정/삭제 (createUser, updateUser, updateUserStatus, deleteUser)
- `UserRoleService`: 역할 관리 (updateUserRoles, addUserRole, removeUserRole)
- `UserPasswordService`: 비밀번호 관리 (resetPassword)
- `UserMapper`: DTO 변환 (기존 UserMapper 재사용)

**예상 결과**: 643 라인 → 각 서비스 150-200 라인 이하

#### 2. RoleCommandService (391 라인)

**현재 책임**:
- 역할 생성/수정/삭제
- 역할 멤버 관리 (추가/삭제/업데이트)
- 역할 권한 관리 (Bulk 업데이트)

**분해 방안**:
- `RoleCommandService`: 역할 CRUD (createRole, updateRole, deleteRole) - 유지
- `RoleMemberCommandService`: 역할 멤버 관리 (addRoleMember, removeRoleMember, updateRoleMembers)
- `RolePermissionCommandService`: 역할 권한 관리 (updateRolePermissions)

**예상 결과**: 391 라인 → 각 서비스 150-200 라인 이하

#### 3. AdminGuardService (352 라인)

**현재 책임**:
- ADMIN 역할 검증
- 권한 검사 (canAccess)
- 권한 목록 조회 (getPermissions, getPermissionSet)
- 캐시 관리

**분해 방안**:
- `AdminGuardService`: ADMIN 역할 검증 (requireAdminRole) - 유지
- `PermissionQueryService`: 권한 조회 (getPermissions, getPermissionSet) - PermissionCalculator 활용
- `PermissionCacheManager`: 캐시 관리 (별도 컴포넌트로 분리)

**예상 결과**: 352 라인 → 각 서비스 150-200 라인 이하

---

## 1) 패키지 구조 표준화

### 현재 구조

```
com.dwp.services.auth
├── controller/
│   ├── AuthController
│   ├── CodeController
│   ├── MenuController
│   ├── admin/
│   │   ├── UserController
│   │   ├── RoleController
│   │   └── ...
│   └── monitoring/
├── service/
│   ├── AuthService
│   ├── MenuService
│   ├── CodeManagementService
│   ├── admin/
│   │   ├── UserManagementService (643 라인)
│   │   ├── RoleManagementService
│   │   └── ...
│   ├── rbac/
│   │   ├── AdminGuardService (352 라인)
│   │   └── PermissionCalculator
│   └── monitoring/
├── dto/
│   ├── admin/ (34 files)
│   ├── monitoring/
│   └── ...
└── repository/
```

### 목표 구조

```
com.dwp.services.auth
├── controller/
│   ├── auth/
│   │   └── AuthController
│   ├── admin/
│   │   ├── user/
│   │   │   └── AdminUserController
│   │   ├── role/
│   │   │   └── AdminRoleController
│   │   └── ...
│   ├── code/
│   │   └── CodeController
│   ├── menu/
│   │   └── MenuController
│   └── monitoring/
│       ├── MonitoringCollectController
│       └── AdminMonitoringController
├── service/
│   ├── auth/
│   │   ├── AuthService
│   │   ├── AuthPolicyService
│   │   ├── AuthLoginService (분해)
│   │   └── AuthPermissionService (분해)
│   ├── admin/
│   │   ├── user/
│   │   │   ├── UserQueryService (분해)
│   │   │   ├── UserCommandService (분해)
│   │   │   ├── UserRoleService (분해)
│   │   │   ├── UserPasswordService (분해)
│   │   │   └── UserMapper (기존 재사용)
│   │   └── role/
│   │       ├── RoleQueryService (기존)
│   │       ├── RoleCommandService (기존, 분해)
│   │       ├── RoleMemberCommandService (분해)
│   │       └── RolePermissionCommandService (분해)
│   ├── code/
│   │   ├── CodeResolver
│   │   ├── CodeUsageService
│   │   ├── CodeUsageQueryService (분해)
│   │   └── CodeUsageAdminCommandService (분해)
│   ├── menu/
│   │   └── MenuService
│   ├── monitoring/
│   │   ├── MonitoringCollectService
│   │   ├── MonitoringQueryService (분해)
│   │   └── AdminMonitoringService
│   ├── rbac/
│   │   ├── AdminGuardService (기존, 분해)
│   │   ├── PermissionCalculator (기존)
│   │   ├── PermissionQueryService (분해)
│   │   └── PermissionCacheManager (분해)
│   └── audit/
│       └── AuditLogService
├── dto/
│   ├── auth/
│   ├── admin/
│   │   ├── user/
│   │   ├── role/
│   │   └── ...
│   ├── code/
│   ├── menu/
│   └── monitoring/
├── repository/
└── util/
    ├── CodeResolver
    └── ValidationHelper (신규)
```

---

## 2) 거대 Service 분해 규칙

### 분해 기준

다음 중 2개 이상 해당하면 분해:
- ✅ 500라인 초과
- ✅ DB 조회 + 변환 + 권한 + 캐시 + 기록 로직이 한 곳에 섞임
- ✅ 메서드가 10개 이상인데 단일 책임이 아님

### 분해 우선순위

1. **UserManagementService** (643 라인) - 최우선
2. **RoleCommandService** (391 라인) - 두 번째
3. **AdminGuardService** (352 라인) - 세 번째

---

## 3) DTO/응답 모델 정리

### 현재 상태

- `dto/admin/`에 34개 파일이 혼재
- Request/Response가 같은 패키지에 있음
- 중복 DTO 가능성

### 정리 방안

- 목적별 패키지로 이동 (`dto/admin/user/`, `dto/admin/role/` 등)
- Request/Response는 별도 파일로 분리 (이미 분리되어 있음)
- 공통 응답 래핑(`ApiResponse<T>`)은 유지

---

## 4) Repository Query 분리

### 현재 상태

- 복잡한 쿼리는 Repository에 `@Query` 어노테이션으로 구현
- QueryDSL 미사용

### 개선 방안

- 복잡한 조회 쿼리는 `query/` 패키지로 분리 (필요시)
- `tenant_id` 조건을 repository 단에서 누락하지 않도록 강제

---

## 5) 로깅/감사/히스토리 기록 공통화

### 현재 상태

- `AuditLogService`: 감사 로그
- `LoginHistoryRepository`: 로그인 히스토리
- `MonitoringCollectService`: 이벤트 로그

### 공통화 방안

- `AuditLogService`는 이미 공통 컴포넌트로 존재 (유지)
- `HistoryRecorder`: 로그인/세션 히스토리 (필요시)
- `EventLogRecorder`: 이벤트 로그 (MonitoringCollectService 활용)

---

## 6) 공통 Validation Helper 추가

### 현재 상태

- Controller에서 반복되는 검증 로직 존재 가능성

### 추가 방안

- `util/ValidationHelper` 클래스 생성
- `requireTenantId()`, `requireAuthHeader()`, `validateCode()`, `normalizeAction()` 메서드 추가
- 기존 유틸이 있으면 재사용

---

## 7) 테스트 유지/보강

### 필수 테스트

- `AuthControllerTest` - 통과 확인
- `CodeUsageControllerTest` - 통과 확인
- `AdminMonitoringControllerTest` - 통과 확인
- `MonitoringCollectControllerTest` - 통과 확인

### 추가 테스트

- 순수 유틸(`normalize/validate`) 단위 테스트 1개 이상 추가

---

## 8) 작업 순서

1. ✅ 리팩토링 대상 TOP 3 선정 및 분석
2. ⏳ 패키지 구조 표준화 (DTO 이동)
3. ⏳ UserManagementService 분해 (최우선)
4. ⏳ RoleCommandService 분해
5. ⏳ AdminGuardService 분해
6. ⏳ 공통 Validation Helper 추가
7. ⏳ 테스트 유지/보강
8. ⏳ 문서화 및 요약

---

## 9) 완료 기준

- ✅ TOP 3 클래스 라인수 before/after 비교
- ✅ 새 패키지 구조 트리
- ✅ 이동/삭제된 파일 리스트
- ✅ 기능/스펙 변경 없음 확인 (호환 유지 체크리스트)
- ✅ 테스트 통과 결과

---

## 10) 예상 효과

- **유지보수성 향상**: 각 서비스가 단일 책임을 가짐
- **확장성 향상**: 새로운 기능 추가 시 기존 코드 영향 최소화
- **테스트 용이성**: 작은 단위로 테스트 작성 용이
- **코드 가독성**: 클래스 크기 감소로 가독성 향상
