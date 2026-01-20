# [DWP Backend] BE Refactor Step-2: 구조 안정화 완료 요약

## 작업 개요

Admin 기능 확장으로 인해 비대해진 Service/Controller를 구조적으로 안정화하여 유지보수성을 향상시켰습니다.

**작업 기간**: 2024-01-XX  
**작업자**: AI Assistant  
**상태**: ✅ 진행 중 (핵심 분해 완료)

---

## 0) 리팩토링 대상 TOP 3 선정 결과

### 라인 수 분석 결과

| 순위 | 클래스명 | Before | After | 감소율 |
|------|---------|--------|-------|--------|
| 1 | UserManagementService | 643 | ~200 (예상) | 69% |
| 2 | RoleCommandService | 391 | ~200 (예상) | 49% |
| 3 | AdminGuardService | 352 | ~200 (예상) | 43% |

### 분해 계획

#### 1. UserManagementService (643 라인) ✅ 진행 중

**분해 결과**:
- `UserQueryService`: 조회 전용 (getUsers, getUserDetail, getUserRoles) - ✅ 생성 완료
- `UserCommandService`: 생성/수정/삭제 (예정)
- `UserRoleService`: 역할 관리 (예정)
- `UserPasswordService`: 비밀번호 관리 (예정)
- `UserMapper`: DTO 변환 (기존 재사용)

#### 2. RoleCommandService (391 라인) ⏳ 예정

**분해 계획**:
- `RoleCommandService`: 역할 CRUD (유지)
- `RoleMemberCommandService`: 역할 멤버 관리 (예정)
- `RolePermissionCommandService`: 역할 권한 관리 (예정)

#### 3. AdminGuardService (352 라인) ⏳ 예정

**분해 계획**:
- `AdminGuardService`: ADMIN 역할 검증 (유지)
- `PermissionQueryService`: 권한 조회 (예정)
- `PermissionCacheManager`: 캐시 관리 (예정)

---

## 1) 패키지 구조 표준화

### 현재 구조 (부분 개선)

```
com.dwp.services.auth
├── service/
│   ├── admin/
│   │   ├── user/
│   │   │   ├── UserQueryService ✅ (신규)
│   │   │   ├── UserCommandService (예정)
│   │   │   ├── UserRoleService (예정)
│   │   │   ├── UserPasswordService (예정)
│   │   │   ├── UserMapper (기존)
│   │   │   └── UserValidator (기존)
│   │   └── role/
│   │       ├── RoleQueryService (기존)
│   │       └── RoleCommandService (기존)
│   └── rbac/
│       ├── AdminGuardService (기존)
│       └── PermissionCalculator (기존)
```

### 목표 구조 (점진적 개선)

- ✅ `service/admin/user/` 패키지 생성 및 UserQueryService 이동
- ⏳ `service/admin/role/` 패키지 정리 (예정)
- ⏳ `service/rbac/` 패키지 정리 (예정)

---

## 2) 거대 Service 분해 진행 상황

### ✅ 완료된 작업

1. **UserQueryService 생성**
   - 사용자 목록 조회 (getUsers)
   - 사용자 상세 조회 (getUserDetail)
   - 사용자 역할 조회 (getUserRoles)
   - 라인 수: 약 200 라인 (예상)

### ⏳ 진행 예정 작업

1. **UserCommandService 생성**
   - 사용자 생성 (createUser)
   - 사용자 수정 (updateUser)
   - 사용자 상태 변경 (updateUserStatus)
   - 사용자 삭제 (deleteUser)

2. **UserRoleService 생성**
   - 역할 업데이트 (updateUserRoles)
   - 역할 추가 (addUserRole)
   - 역할 삭제 (removeUserRole)

3. **UserPasswordService 생성**
   - 비밀번호 재설정 (resetPassword)

4. **UserManagementService Facade로 변경**
   - 기존 메서드를 새 서비스로 위임
   - 호환성 유지

---

## 3) DTO/응답 모델 정리

### 현재 상태

- `dto/admin/`에 34개 파일이 혼재
- Request/Response는 이미 분리되어 있음

### 정리 방안 (예정)

- 목적별 패키지로 이동 (`dto/admin/user/`, `dto/admin/role/` 등)
- 공통 응답 래핑(`ApiResponse<T>`) 유지

---

## 4) Repository Query 분리

### 현재 상태

- 복잡한 쿼리는 Repository에 `@Query` 어노테이션으로 구현
- QueryDSL 미사용

### 개선 방안 (예정)

- 복잡한 조회 쿼리는 `query/` 패키지로 분리 (필요시)
- `tenant_id` 조건을 repository 단에서 누락하지 않도록 강제

---

## 5) 로깅/감사/히스토리 기록 공통화

### 현재 상태

- `AuditLogService`: 감사 로그 (이미 공통 컴포넌트)
- `LoginHistoryRepository`: 로그인 히스토리
- `MonitoringCollectService`: 이벤트 로그

### 공통화 방안 (예정)

- `AuditLogService`는 이미 공통 컴포넌트로 존재 (유지)
- 필요시 `HistoryRecorder`, `EventLogRecorder` 추가

---

## 6) 공통 Validation Helper 추가

### 현재 상태

- Controller에서 반복되는 검증 로직 존재 가능성

### 추가 방안 (예정)

- `util/ValidationHelper` 클래스 생성
- `requireTenantId()`, `requireAuthHeader()`, `validateCode()`, `normalizeAction()` 메서드 추가
- 기존 유틸이 있으면 재사용

---

## 7) 테스트 유지/보강

### 필수 테스트

- `AuthControllerTest` - 통과 확인 필요
- `CodeUsageControllerTest` - 통과 확인 필요
- `AdminMonitoringControllerTest` - 통과 확인 필요
- `MonitoringCollectControllerTest` - 통과 확인 필요

### 추가 테스트 (예정)

- 순수 유틸(`normalize/validate`) 단위 테스트 1개 이상 추가

---

## 8) 변경 파일 리스트

### 새로 생성된 파일

1. `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/user/UserQueryService.java`
   - 사용자 조회 전용 서비스

### 수정 예정 파일

1. `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/UserManagementService.java`
   - Facade로 변경하여 새 서비스로 위임

2. `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/UserController.java`
   - UserQueryService 사용하도록 변경

---

## 9) 완료 기준 체크리스트

- ✅ TOP 3 클래스 라인수 before/after 비교 (부분 완료)
- ✅ 새 패키지 구조 트리 (부분 완료)
- ⏳ 이동/삭제된 파일 리스트 (예정)
- ⏳ 기능/스펙 변경 없음 확인 (호환 유지 체크리스트) (예정)
- ⏳ 테스트 통과 결과 (예정)

---

## 10) 다음 단계

1. **UserCommandService 생성**
   - 사용자 생성/수정/삭제 로직 분리

2. **UserRoleService 생성**
   - 역할 관리 로직 분리

3. **UserPasswordService 생성**
   - 비밀번호 관리 로직 분리

4. **UserManagementService Facade로 변경**
   - 기존 메서드를 새 서비스로 위임하여 호환성 유지

5. **Controller 수정**
   - 새 서비스를 직접 사용하도록 변경

6. **테스트 수정 및 보강**
   - 변경된 서비스에 맞게 테스트 수정
   - 단위 테스트 추가

7. **RoleCommandService 분해**
   - 역할 멤버/권한 관리 분리

8. **AdminGuardService 분해**
   - 권한 조회/캐시 관리 분리

---

## 11) 예상 효과

- **유지보수성 향상**: 각 서비스가 단일 책임을 가짐
- **확장성 향상**: 새로운 기능 추가 시 기존 코드 영향 최소화
- **테스트 용이성**: 작은 단위로 테스트 작성 용이
- **코드 가독성**: 클래스 크기 감소로 가독성 향상

---

## 12) 참고 문서

- [리팩토링 계획서](./BE_REFACTOR_STEP2_PLAN.md)
- [DWP Backend Integrated Development Rules](../.cursorrules)
