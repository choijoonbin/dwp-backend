# Release Notes: PR-04 ~ PR-10 통합 배포

**배포일**: 2026-01-20  
**버전**: v1.0.0  
**상태**: 운영 준비 완료

---

## 개요

PR-04부터 PR-10까지의 기능을 통합하여 운영 수준의 Admin 관리 기능을 완성했습니다. 리소스/메뉴/코드/코드사용정의/감사로그/RBAC enforcement/SSO 정책이 모두 구현되었습니다.

---

## PR별 기능 요약

### PR-04: Resource CRUD Standardization ✅

**주요 기능**:
- Resources 운영 CRUD API 완성 (목록/생성/수정/삭제)
- 중복 키 검증 (409 Conflict)
- 하위 리소스 존재 시 삭제 차단 (409 Conflict)
- CodeResolver 기반 검증 (resourceCategory, resourceKind, eventActions)
- 감사로그 자동 기록

**API**:
- `GET /api/admin/resources` (목록 조회, 필터 지원)
- `POST /api/admin/resources` (생성)
- `PATCH /api/admin/resources/{id}` (수정)
- `DELETE /api/admin/resources/{id}` (삭제)

**Breaking Change**: 없음

---

### PR-05: Menu Management CRUD + Tree Structure ✅

**주요 기능**:
- Menu 운영 CRUD API 완성
- Tree 구조 조회 (`GET /api/admin/menus/tree`)
- 정렬/이동 API (`PUT /api/admin/menus/reorder`)
- Menu ↔ Resource 자동 동기화 (menuKey = resourceKey)
- 하위 메뉴 존재 시 삭제 차단 (409 Conflict)

**API**:
- `GET /api/admin/menus` (목록 조회)
- `GET /api/admin/menus/tree` (트리 조회)
- `POST /api/admin/menus` (생성)
- `PATCH /api/admin/menus/{id}` (수정)
- `DELETE /api/admin/menus/{id}` (삭제)
- `PUT /api/admin/menus/reorder` (정렬/이동)

**Breaking Change**: 없음

**주의사항**: Menu 생성 시 `com_resources`에 자동으로 MENU 리소스가 생성됩니다. 기존 메뉴는 수동 동기화가 필요할 수 있습니다.

---

### PR-06: CodeGroups/Codes Operational CRUD + Tenant-Specific Code Policy ✅

**주요 기능**:
- CodeGroups CRUD API 완성
- Codes CRUD API 완성 (tenant 분리 지원)
- 테넌트별 코드 정책 (tenant_id = null: 공통, tenant_id = {tenant}: 전용)
- 메뉴별 코드 조회 보안 강화 (ADMIN 권한 + resourceKey 접근 권한)
- 캐시 무효화 자동 처리

**API**:
- `GET /api/admin/code-groups` (그룹 목록)
- `POST /api/admin/code-groups` (그룹 생성)
- `PATCH /api/admin/code-groups/{id}` (그룹 수정)
- `DELETE /api/admin/code-groups/{id}` (그룹 삭제)
- `GET /api/admin/codes` (코드 목록, tenantScope 필터)
- `POST /api/admin/codes` (코드 생성)
- `PATCH /api/admin/codes/{id}` (코드 수정)
- `DELETE /api/admin/codes/{id}` (코드 삭제)

**Breaking Change**: 없음

**주의사항**: `sys_codes`에 `tenant_id` 컬럼이 추가되었습니다. 기존 코드는 `tenant_id = null`로 처리됩니다.

---

### PR-07: CodeUsage Operational Level Enhancement ✅

**주요 기능**:
- CodeUsage 목록 조회 고도화 (필터 지원)
- 생성/수정 시 검증 강화 (resourceKey/groupKey 존재, tenantId 일치)
- 메뉴별 코드 조회 성능/보안 강화 (tenant 우선 → common fallback)
- 캐시 무효화 자동 처리

**API**:
- `GET /api/admin/code-usages` (목록 조회, 필터 지원)
- `POST /api/admin/code-usages` (생성)
- `PATCH /api/admin/code-usages/{id}` (수정)
- `DELETE /api/admin/code-usages/{id}` (삭제)
- `GET /api/admin/codes/usage?resourceKey=...` (메뉴별 코드 조회)

**Breaking Change**: 없음

---

### PR-08: Audit Logs Query API + Filters/Search + Excel Export ✅

**주요 기능**:
- Audit Logs 조회 API 완성 (필터/검색/페이징)
- before/after JSON size 정책 (최대 10KB, 초과 시 truncate)
- Excel 다운로드 API (`POST /api/admin/audit-logs/export`)

**API**:
- `GET /api/admin/audit-logs` (목록 조회, 필터 지원)
- `POST /api/admin/audit-logs/export` (Excel 다운로드)

**Breaking Change**: 없음

**주의사항**: 대량 데이터 export 시 성능 이슈가 있을 수 있습니다. 향후 비동기 taskId 방식으로 개선 예정입니다.

---

### PR-09: RBAC Enforcement Enhancement ✅

**주요 기능**:
- `PermissionEvaluator` 표준 유틸 생성
- Admin CRUD API에 권한 체크 통합 (VIEW/EDIT 매핑)
- 서버 차단 보장 (FE 숨김과 무관하게 403 반환)

**권한 매핑 정책**:
- LIST/READ → VIEW 권한
- CREATE/UPDATE/DELETE → EDIT 권한

**Breaking Change**: 없음

**주의사항**: 권한이 없는 사용자가 Admin CRUD API를 호출하면 403 Forbidden이 반환됩니다. 기존에 권한 없이 접근하던 경우가 있다면 권한을 부여해야 합니다.

---

### PR-10: SSO (OIDC/SAML) Actual Integration Commencement ✅

**주요 기능**:
- 정책 기반 로그인 흐름 (`GET /api/auth/policy`)
- OIDC 연동 1차 구현 (Azure AD 예시)
- SAML Skeleton 제공
- 로그인 통합 응답 (LOCAL/SSO 동일 JWT 모델)
- 로그인 이력 강화 (provider_type, 실패 사유 표준화)

**API**:
- `GET /api/auth/policy` (로그인 정책 조회)
- `GET /api/auth/idp` (Identity Provider 목록)
- `GET /api/auth/oidc/login?providerKey=...` (OIDC 로그인 시작)
- `GET /api/auth/oidc/callback` (OIDC 콜백)
- `GET /api/auth/saml/login?providerKey=...` (SAML 로그인 시작)
- `POST /api/auth/saml/callback` (SAML 콜백)

**Breaking Change**: 없음

**주의사항**: OIDC/SAML 실제 연동은 Identity Provider 설정이 필요합니다. 현재는 Skeleton만 제공되며, 실제 연동은 다음 PR에서 완성 예정입니다.

---

## 운영 시 주의사항 (Top 5)

### 1. 멀티테넌시 격리 필수 확인 ⚠️

모든 Admin CRUD/조회는 `tenant_id` 필터가 절대 누락되면 안 됩니다. 테스트에서 tenant A/B 데이터가 섞이는 케이스가 없는지 반드시 검증하세요.

**검증 방법**:
```bash
# Tenant A에서 데이터 생성
curl -H "X-Tenant-ID: 1" POST /api/admin/menus ...

# Tenant B에서 조회 시 데이터가 없어야 함
curl -H "X-Tenant-ID: 2" GET /api/admin/menus
```

---

### 2. 캐시 무효화 로그 모니터링 📊

CodeResolver 및 CodeUsage 캐시 무효화는 `INFO` 레벨로 로그가 기록됩니다. 운영 중 캐시 무효화가 정상 동작하는지 모니터링하세요.

**로그 예시**:
```
INFO: Code cache cleared for groupKey: RESOURCE_TYPE
INFO: Code usage cache cleared: tenantId=1, resourceKey=menu.admin.users
```

---

### 3. Audit Log before/after JSON 크기 제한 📏

Audit Log의 `before`/`after` JSON은 최대 10KB로 제한됩니다. 초과 시 자동으로 truncate되며 `truncated=true` 플래그가 추가됩니다.

**영향**: 대용량 객체 변경 시 일부 데이터가 잘릴 수 있습니다. 필요시 별도 이벤트 로그로 기록하세요.

---

### 4. RBAC Enforcement 서버 차단 확인 🔒

FE에서 버튼을 숨겨도 URL 직접 접근 시 서버가 403을 반환하는지 확인하세요. `PermissionEvaluator.requirePermission()`이 모든 Admin CRUD에 통합되었습니다.

**검증 방법**:
```bash
# 권한 없는 토큰으로 호출
curl -H "Authorization: Bearer <token_without_permission>" \
     POST /api/admin/users
# → 403 Forbidden 반환되어야 함
```

---

### 5. Menu ↔ Resource 동기화 정책 확인 🔄

Menu 생성 시 `com_resources`에 자동으로 MENU 리소스가 생성됩니다 (`menuKey = resourceKey`). 기존 메뉴는 수동 동기화가 필요할 수 있습니다.

**동기화 확인**:
```sql
-- Menu가 있지만 Resource가 없는 경우 확인
SELECT m.menu_key, m.menu_name
FROM sys_menus m
LEFT JOIN com_resources r ON m.menu_key = r.key AND m.tenant_id = r.tenant_id
WHERE r.resource_id IS NULL;
```

---

## 롤백 전략

### 데이터베이스 마이그레이션 롤백

**주의**: PR-04~PR-10에서 추가된 마이그레이션은 데이터 손실이 있을 수 있습니다.

**롤백 절차**:
1. Flyway 마이그레이션 롤백:
   ```bash
   ./gradlew :dwp-auth-server:flywayRepair
   # 또는 수동으로 VXX__*.sql 파일 삭제
   ```

2. 데이터 백업 복원:
   ```bash
   pg_restore -d dwp_db backup_before_pr04_pr10.dump
   ```

### 애플리케이션 롤백

**롤백 절차**:
1. 이전 버전으로 배포:
   ```bash
   git checkout <previous_tag>
   ./gradlew clean build
   # 배포 프로세스에 따라 배포
   ```

2. 캐시 초기화:
   ```bash
   # Redis 캐시 초기화 (있는 경우)
   redis-cli FLUSHALL
   ```

---

## 다음 단계 권장사항

### 단기 (1~2주)
1. **통합 테스트 보강**: 실제 운영 데이터로 통합 테스트 수행
2. **성능 테스트**: 대량 데이터 조회/export 성능 검증
3. **모니터링 대시보드**: Audit Log, 캐시 무효화 로그 모니터링 대시보드 구축

### 중기 (1개월)
1. **SSO 실제 연동 완성**: OIDC/SAML 실제 연동 완료
2. **비동기 Export**: Audit Log Excel export를 비동기 taskId 방식으로 개선
3. **권한 관리 UI**: Admin에서 권한 관리 UI 구축

### 장기 (3개월)
1. **대량 Import/Export**: Menu, Code, CodeUsage 대량 import/export 기능
2. **권한 이력 관리**: 권한 변경 이력 추적 및 롤백 기능
3. **멀티테넌시 확장**: 테넌트별 커스터마이징 확장

---

## 문의 및 지원

문제 발생 시 다음을 확인하세요:
1. `docs/ADMIN_API_QUICKREF.md`: API 사용법 참조
2. `docs/SECURITY_RBAC_ENFORCEMENT.md`: 권한 정책 참조
3. `docs/CODE_TENANT_POLICY.md`: 코드 정책 참조

---

**작성일**: 2026-01-20  
**작성자**: DWP Backend Team
