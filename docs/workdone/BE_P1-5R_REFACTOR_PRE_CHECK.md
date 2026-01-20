# BE P1-5R: Admin/IAM Service 리팩토링 사전 점검

## 0) 사전 점검 결과

### 1) 현재 가장 비대한 클래스 Top 3 (라인수 기준)

| 순위 | 클래스명 | 라인수 | 위치 |
|------|---------|--------|------|
| 1 | `RoleManagementService` | 483 lines | `service/admin/RoleManagementService.java` |
| 2 | `UserManagementService` | 460 lines | `service/admin/UserManagementService.java` |
| 3 | `AdminGuardService` | 436 lines | `service/rbac/AdminGuardService.java` |

### 2) 비대해진 이유 분석

#### RoleManagementService (483 lines)
- **CRUD 로직**: Role 생성/수정/삭제/조회 (약 150 lines)
- **Role Members 관리**: 조회/추가/삭제/업데이트 (약 150 lines)
- **Role Permissions Bulk**: 조회/업데이트 (resourceKey/permissionCode 기반, 약 100 lines)
- **검증 로직**: CodeResolver 기반 검증, 중복 체크 (약 50 lines)
- **매핑 로직**: Entity → DTO 변환 (약 30 lines)
- **감사 로그**: 각 작업마다 auditLogService 호출 (약 3 lines × 10개 메서드)

#### UserManagementService (460 lines)
- **CRUD 로직**: User 생성/수정/삭제/조회 (약 200 lines)
- **UserAccount 관리**: LOCAL 계정 생성/비밀번호 재설정 (약 80 lines)
- **Role 관리**: 사용자 역할 조회/업데이트 (약 60 lines)
- **검증 로직**: 이메일 중복, 부서 존재 확인, CodeResolver 검증 (약 50 lines)
- **매핑 로직**: `toUserSummary`, `copyUser` (약 70 lines)

#### AdminGuardService (436 lines)
- **권한 계산 로직**: `canAccess`, `getPermissions`, `getPermissionSet` (약 200 lines)
- **캐싱**: Caffeine 캐시 3개 (adminRoleCache, permissionsCache, permissionSetCache) (약 50 lines)
- **ADMIN 역할 검증**: `hasAdminRole`, `isAdmin`, `requireAdminRole` (약 50 lines)
- **권한 계산 핵심**: USER + DEPARTMENT role 합산, DENY 우선 정책 (약 100 lines)
- **캐시 무효화**: `invalidateCache` (약 20 lines)
- **내부 클래스**: `PermissionTuple` (약 16 lines)

### 3) 리팩토링 시 깨질 위험이 큰 포인트 3개 및 방지책

#### 1) 트랜잭션 경계 변경
**위험**: Command/Query 분리 시 트랜잭션 경계가 변경되어 데이터 일관성 문제 발생 가능

**방지책**:
- QueryService: `@Transactional(readOnly = true)` 명시
- CommandService: `@Transactional` 명시
- 기존 테스트에서 트랜잭션 롤백 확인
- Controller에서 트랜잭션 경계 유지 (Controller에 `@Transactional` 금지)

#### 2) tenant_id 필터 누락
**위험**: Repository 호출 시 `tenantId` 필터 누락으로 멀티테넌시 데이터 유출 가능

**방지책**:
- 모든 Repository 메서드에 `tenantId` 파라미터 필수
- Query 메서드명에 `ByTenantId` 포함 강제
- 기존 테스트에서 tenant isolation 검증 유지
- 리팩토링 후 테스트에서 다른 tenantId로 접근 시 403/404 확인

#### 3) 응답 DTO 구조 변경
**위험**: 매핑 로직 분리 시 응답 구조 변경으로 프론트엔드 호환성 깨짐

**방지책**:
- 기존 DTO 클래스 그대로 유지
- Mapper 클래스에서 기존 매핑 로직 그대로 복사
- 기존 테스트의 응답 검증 로직 유지
- API 스펙 문서와 실제 응답 비교

---

## 리팩토링 우선순위

1. **AdminGuardService** (436 lines) - 권한 계산 로직 분리 (가장 복잡)
2. **RoleManagementService** (483 lines) - CRUD/Query/Command 분리
3. **UserManagementService** (460 lines) - CRUD/Query/Command 분리
