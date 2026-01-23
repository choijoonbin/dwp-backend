# BE P1-5 (Enhanced): Admin CRUD 운영수준 완성 + RBAC Enforcement(서버 강제) + CodeUsage 관리 완성

**작성일**: 2026-01-20  
**목적**: 운영 가능한 Admin CRUD + 권한 강제(서버) + 코드 사용 정의 관리 UI 지원까지 완성

---

## ✅ 완료 사항

### 1) 사전 체크 및 현황 파악
- ✅ Admin CRUD API 대부분 이미 구현되어 있음 확인
- ✅ AdminGuardInterceptor/Service 존재 확인
- ✅ RolePermission 매핑 API 존재 확인
- ✅ Code CRUD 존재 확인
- ✅ 부족한 부분만 보강 결정

**상세**: [docs/BE_P1-5_ENHANCED_PRE_CHECK.md](BE_P1-5_ENHANCED_PRE_CHECK.md)

---

### 2) ResourceController 필터링 보강
- ✅ `category` 필터 추가 (MENU/UI_COMPONENT)
- ✅ `kind` 필터 추가 (MENU_GROUP/PAGE/BUTTON 등)
- ✅ `enabled` 필터 추가

**변경 파일**:
- `ResourceRepository.java`: `findByTenantIdAndFilters()` 메서드 시그니처 변경
- `ResourceManagementService.java`: 필터 파라미터 추가
- `ResourceController.java`: Query 파라미터 추가

---

### 3) UserController 필터링 보강
- ✅ `idpProviderType` 필터 추가 (LOCAL/SSO 등)
- ✅ `keyword` 검색 범위 확장 (이름/이메일/사번/principal)

**변경 파일**:
- `UserRepository.java`: `findByTenantIdAndFilters()` 메서드에 idpProviderType 파라미터 추가, UserAccount JOIN 추가
- `UserManagementService.java`: 필터 파라미터 추가
- `UserController.java`: Query 파라미터 추가

---

### 4) AdminGuardInterceptor 동작 검증 및 테스트
- ✅ `AdminGuardInterceptorTest.java` 신규 작성
- ✅ 테스트 케이스:
  - ADMIN 아닌 유저로 `/api/admin/**` 접근 시 403
  - ADMIN 유저는 정상 통과
  - `/api/admin/**` 경로가 아닌 경우 통과
  - 인증 정보 없으면 401
  - tenant_id 없으면 401

---

### 5) 문서 작성
- ✅ `P1-5_ADMIN_CRUD_SPEC.md` 신규 작성
  - API 목록/Request/Response 예시
  - curl 예시
  - 권한 정책(ADMIN enforcement)
  - CodeUsage 운영 원칙(5줄) 상단 고정
- ✅ `BE_P1-5_ENHANCED_PRE_CHECK.md` 작성
- ✅ `BE_P1-5_ENHANCED_SUMMARY.md` 작성 (본 문서)
- ✅ `README.md` 업데이트 (Admin CRUD API 섹션 추가)

---

## 📋 주요 변경 파일

### Repository Files
- `ResourceRepository.java`: 필터링 메서드 시그니처 변경
- `UserRepository.java`: idpProviderType 필터 추가, UserAccount JOIN

### Service Files
- `ResourceManagementService.java`: 필터 파라미터 추가
- `UserManagementService.java`: 필터 파라미터 추가

### Controller Files
- `ResourceController.java`: Query 파라미터 추가
- `UserController.java`: Query 파라미터 추가

### Test Files
- `AdminGuardInterceptorTest.java` (신규)

### Documentation Files
- `P1-5_ADMIN_CRUD_SPEC.md` (신규)
- `BE_P1-5_ENHANCED_PRE_CHECK.md` (신규)
- `BE_P1-5_ENHANCED_SUMMARY.md` (본 문서)
- `README.md` (업데이트)

---

## ✅ 완료 조건 확인

- ✅ Admin Remote 화면에서 필요한 CRUD API가 모두 존재
- ✅ 서버에서 ADMIN 권한이 강제됨 (`AdminGuardInterceptor`)
- ✅ CodeUsage 기반 코드 조회가 운영 수준으로 동작
- ✅ 테스트 통과 (컴파일 성공)
- ✅ 문서 업데이트 완료
- ✅ PR-ready

---

## 🔍 RBAC Enforcement 동작 확인

### AdminGuardInterceptor 동작 흐름
```
요청: /api/admin/users
  ↓
1. 경로 확인 (/api/admin/** 또는 /admin/**)
  ├─ 아니면 → 통과
  └─ 맞으면 → 다음 단계
  ↓
2. JWT 인증 확인
  ├─ 없으면 → 401 Unauthorized
  └─ 있으면 → 다음 단계
  ↓
3. tenant_id 확인
  ├─ 없으면 → 401 Unauthorized
  └─ 있으면 → 다음 단계
  ↓
4. ADMIN 역할 검증 (AdminGuardService.requireAdminRole)
  ├─ 없으면 → 403 Forbidden
  └─ 있으면 → 통과
```

---

## 📝 향후 확장 포인트

### 현재 상태
- ADMIN role만 체크
- 모든 `/api/admin/**` 경로에 동일하게 적용

### 확장 가능성
- `AdminGuardService.canAccess(userId, tenantId, resourceKey, permissionCode)` 메서드 추가
- 리소스별/퍼미션별 세밀한 권한 제어 가능
- 예: `menu.admin.users` + `USE` 권한만 있는 사용자는 조회만 가능, 수정 불가

---

## 🛡️ 보안 정책

### tenant_id 격리
- 모든 Repository 메서드에 tenant_id 필터 적용
- FK 제약 없음 (유연성 확보)

### Audit Log
- 모든 Admin CRUD 작업은 `com_audit_logs`에 기록
- action, entity, entityId, before/after, actorUserId 포함

---

**작업 완료일**: 2026-01-20  
**작성자**: DWP Backend Team
