# BE P1-5R Step-2: 사전 점검 결과

## 0) Step-1 결과 점검

### 1) Auth/Role/Code/Monitoring 중 가장 큰 클래스 TOP3

| 순위 | 클래스명 | 라인수 | 위치 |
|------|---------|--------|------|
| 1 | `UserManagementService` | 460 lines | `service/admin/UserManagementService.java` |
| 2 | `RoleCommandService` | 391 lines | `service/admin/role/RoleCommandService.java` |
| 3 | `AdminGuardService` | 326 lines | `service/rbac/AdminGuardService.java` |

### 2) Query/Command 분리된 패키지 구조

```
com.dwp.services.auth.service/
├─ admin/
│  ├─ UserManagementService.java          (460 lines - 리팩토링 필요)
│  ├─ CodeUsageService.java                (261 lines)
│  ├─ ResourceManagementService.java       (260 lines)
│  ├─ DepartmentManagementService.java    (176 lines)
│  └─ role/                                ✅ Step-1 완료
│     ├─ RoleQueryService.java             (조회 전용)
│     └─ RoleCommandService.java           (391 lines - 변경 전용)
├─ rbac/
│  ├─ AdminGuardService.java               (326 lines)
│  └─ PermissionCalculator.java            ✅ Step-1 완료 (237 lines)
├─ monitoring/
│  ├─ MonitoringCollectService.java        (309 lines)
│  └─ AdminMonitoringService.java          (216 lines)
├─ audit/
│  └─ AuditLogService.java                 (180 lines)
├─ AuthService.java                         (290 lines)
├─ CodeManagementService.java               (321 lines)
├─ MenuService.java                         (217 lines)
└─ MonitoringService.java                   (236 lines)
```

### 3) Controller 비즈니스 로직 체크

✅ **Controller는 Service에 위임만 수행**: 비즈니스 로직 없음
- 모든 Controller는 `ApiResponse.success(service.method())` 패턴 사용
- 예외 처리도 Service에서 수행
- Controller는 단순 위임 역할만 수행

## Step-2 작업 계획

### 1) Validator/Mapper 분리 대상
- `UserManagementService`: Validator/Mapper 분리 필요
- `RoleCommandService`: Validator 분리 필요
- `CodeManagementService`: Validator 분리 필요
- `MonitoringCollectService`: Validator 분리 필요

### 2) RBAC 계산 로직 통일
- ✅ `PermissionCalculator`로 이미 분리됨
- 정렬 안정성 보장 필요

### 3) 캐시 정책 정리
- `CodeResolver`: 캐시 key 규칙 명시 필요
- `CodeUsageService`: 캐시 무효화 통일 필요

### 4) Monitoring 이벤트 수집 보강
- action normalize 안정화 필요
- resource_kind 저장 보장 필요
