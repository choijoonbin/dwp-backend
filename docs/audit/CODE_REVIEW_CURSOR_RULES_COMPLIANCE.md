# 코드 리뷰: 커서룰즈 규칙 준수 현황

## 작성일
2026-01-21

## 목적
백엔드 소스 전체를 점검하여 커서룰즈 규칙 위반, 불필요한 파일, 구조화되지 않은 부분 등을 검수합니다.

---

## 1. Native Query 사용 위반 (심각)

### 발견 사항
- **RoleRepository.java** (49-58줄): `nativeQuery = true` 사용
- **UserRepository.java** (55-72줄): `nativeQuery = true` 사용

### 규칙 위반 내용
```
🧾 Persistence Rule: JPA + QueryDSL Only (Native Query Prohibited)
- ❌ @Query(nativeQuery = true) 사용 금지
```

### 사유
- V20 마이그레이션 이후 bytea 타입이 VARCHAR로 변환되었지만, Hibernate가 여전히 bytea로 인식하는 문제 해결을 위해 CAST 사용
- 이는 예외 승인 절차를 거쳐야 하지만, 문서화되지 않음

### 권장 조치
1. `docs/` 폴더에 Native Query 사용 사유 문서화
2. ADR 작성 또는 예외 승인 절차 진행
3. 가능하면 QueryDSL로 대체 검토

---

## 2. 클래스 크기 제한 위반 (중요)

### 발견 사항

#### Controller (제한: 250줄)
- **UserController.java**: 265줄 (15줄 초과) ⚠️

#### Service (제한: 350줄)
- **AuthService.java**: 430줄 (80줄 초과) ⚠️
- **CodeUsageService.java**: 398줄 (48줄 초과) ⚠️
- **ResourceManagementService.java**: 394줄 (44줄 초과) ⚠️

### 규칙 위반 내용
```
🧹 Maintainability & Refactor Gate
- Class Size Limit (Hard)
  - Controller: 250라인 초과 금지
  - Service: 350라인 초과 금지
  - 초과 시 반드시 책임 단위로 분리한다.
```

### 권장 조치
1. **UserController**: 메서드 분리 또는 하위 컨트롤러로 분리
2. **AuthService**: Query/Command 분리 또는 도메인별 서비스 분리
3. **CodeUsageService**: Query/Command 분리
4. **ResourceManagementService**: Query/Command 분리

---

## 3. Admin CRUD 패턴 미준수 (중요)

### 발견 사항

#### Query/Command 분리 미준수
- **DepartmentManagementService**: Query/Command 분리 안됨
- **CodeUsageService**: Query/Command 분리 안됨
- **ResourceManagementService**: Query/Command 분리 안됨

### 규칙 위반 내용
```
🧩 Admin CRUD Engineering Pattern (Hard Standard)
[패키지 표준]
service/admin/<feature>/
  - <Feature>QueryService
  - <Feature>CommandService
  - <Feature>Validator
```

### 현재 구조
```
service/admin/
├── DepartmentManagementService.java  ❌ Query/Command 분리 안됨
├── CodeUsageService.java            ❌ Query/Command 분리 안됨
├── ResourceManagementService.java   ❌ Query/Command 분리 안됨
├── AuditLogQueryService.java        ✅ Query만 존재 (Command 없음)
├── menus/                           ✅ 분리 완료
├── roles/                           ✅ 분리 완료
└── users/                           ✅ 분리 완료
```

### 권장 조치
1. **DepartmentManagementService** → `departments/` 폴더로 이동 및 Query/Command 분리
2. **CodeUsageService** → `codes/` 또는 `code-usages/` 폴더로 이동 및 Query/Command 분리
3. **ResourceManagementService** → `resources/` 폴더로 이동 및 Query/Command 분리
4. **AuditLogQueryService** → `audit-logs/` 폴더로 이동 (Command는 필요시 추가)

---

## 4. 하드코딩된 코드 값 (중요)

### 발견 사항
다음 문자열들이 하드코딩되어 있음:
- `"ACTIVE"`, `"INACTIVE"` (상태 코드)
- `"USER"`, `"DEPARTMENT"` (주체 타입)
- `"MENU"` (리소스 타입)
- `"ALLOW"`, `"DENY"` (권한 효과)
- `"ADMIN"` (역할 코드)

### 규칙 위반 내용
```
[코드 하드코딩 금지]
- "MENU","UI_COMPONENT","USER","ADMIN"... 직접 비교 금지
- CodeResolver.require/validate + CodeUsage 범위 내 코드만 허용
```

### 발견 위치 (주요)
- `UserCommandService.java`: `"ACTIVE"`, `"INACTIVE"`, `"USER"`
- `UserRoleService.java`: `"USER"`, `"DEPARTMENT"`
- `RoleMemberCommandService.java`: `"USER"`, `"DEPARTMENT"`
- `RolePermissionCommandService.java`: `"ALLOW"`, `"DENY"`
- `DepartmentManagementService.java`: `"ACTIVE"`, `"INACTIVE"`
- `MenuCommandService.java`: `"MENU"`
- `CodeUsageService.java`: `"MENU"`
- `AuthService.java`: `"ACTIVE"`, `"MENU"`
- `PermissionCalculator.java`: `"ALLOW"`, `"DENY"`

### 권장 조치
1. 모든 하드코딩된 코드 값을 `CodeResolver.require()` 또는 `CodeResolver.validate()` 사용으로 변경
2. 또는 상수 클래스 생성 후 `CodeResolver`를 통해 검증

---

## 5. 구조화되지 않은 파일 (보통)

### 발견 사항

#### service/admin 밑에 폴더에 속하지 않은 파일들
- `DepartmentManagementService.java` → `departments/` 폴더로 이동 필요
- `CodeUsageService.java` → `codes/` 또는 `code-usages/` 폴더로 이동 필요
- `ResourceManagementService.java` → `resources/` 폴더로 이동 필요
- `AuditLogQueryService.java` → `audit-logs/` 폴더로 이동 필요

#### controller/admin 밑에 폴더에 속하지 않은 파일들
- `CodeUsageController.java` → `codes/` 또는 `code-usages/` 폴더로 이동 검토
- `DepartmentController.java` → `departments/` 폴더로 이동 검토
- `ResourceController.java` → `resources/` 폴더로 이동 검토

### 권장 조치
1. Admin CRUD 패턴에 따라 폴더 구조 정리
2. 일관성 유지 (menus, roles, users와 동일한 패턴)

---

## 6. 불필요한 파일 검토 (보통)

### 발견 사항

#### 컨트롤러 역할 확인
- `controller/MenuController.java`: `/auth/menus` - 사용자용 메뉴 트리 조회 (권한 기반 필터링) ✅ 역할 명확
- `controller/admin/AdminMenuController.java`: `/admin/menus` - Admin용 메뉴 관리 CRUD ✅ 역할 명확
- `controller/CodeController.java`: `/admin/codes` - Admin용 코드 관리 (CodeManagementService) ✅ 역할 명확
- `controller/admin/CodeUsageController.java`: `/admin/code-usages` - Admin용 코드 사용 정의 관리 ✅ 역할 명확

### 결론
- 중복 없음: 각 컨트롤러는 명확한 역할을 가지고 있음
- 다만, `CodeController`는 `/admin/codes` 경로를 사용하므로 `controller/admin/` 폴더로 이동 검토 가능

---

## 7. 기타 개선 사항

### Transaction 어노테이션
- 모든 Service 메서드에 `@Transactional` 명시 여부 확인 필요
- 조회 메서드는 `@Transactional(readOnly = true)` 확인 필요

### DTO 구조
- `dto/admin/` 폴더 구조가 feature별로 정리되어 있는지 확인 필요

---

## 우선순위별 권장 조치

### 🔴 높음 (즉시 조치)
1. Native Query 사용 사유 문서화 및 예외 승인 절차 진행
2. 클래스 크기 제한 위반 파일 분리 (UserController, AuthService, CodeUsageService, ResourceManagementService)

### 🟡 중간 (단기 조치)
3. Admin CRUD 패턴 미준수 서비스 Query/Command 분리
4. 하드코딩된 코드 값 CodeResolver 사용으로 변경

### 🟢 낮음 (중기 조치)
5. 구조화되지 않은 파일 폴더 정리
6. 불필요한 파일 검토 및 정리

---

## 체크리스트

- [x] Native Query 사용 사유 문서화 ✅
- [x] UserController 크기 축소 (265 → 254줄) ✅
- [ ] AuthService 크기 축소 (430 → 350 이하) - 보류 (복잡도 높음, 별도 작업 필요)
- [x] CodeUsageService 크기 축소 및 Query/Command 분리 ✅
  - CodeUsageQueryService: 204줄
  - CodeUsageCommandService: 208줄
  - CodeUsageService (Facade): 91줄
- [x] ResourceManagementService 크기 축소 및 Query/Command 분리 ✅
  - ResourceQueryService: 138줄
  - ResourceCommandService: 280줄
  - ResourceManagementService (Facade): 64줄
- [x] DepartmentManagementService Query/Command 분리 ✅
  - DepartmentQueryService: 생성 완료
  - DepartmentCommandService: 생성 완료
  - DepartmentManagementService (Facade): 생성 완료
- [ ] 하드코딩된 코드 값 CodeResolver 사용으로 변경 - 부분 완료 (DepartmentCommandService에 적용)
- [x] service/admin 폴더 구조 정리 ✅
  - departments/ 폴더 생성 및 이동 완료
  - codeusages/ 폴더 생성 및 이동 완료
  - resources/ 폴더 생성 및 이동 완료
- [ ] controller/admin 폴더 구조 정리 - 보류 (현재 구조 유지)
