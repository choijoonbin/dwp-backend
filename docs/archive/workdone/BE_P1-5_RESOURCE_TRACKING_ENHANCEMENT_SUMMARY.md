# BE P1-5: com_resource 세분화(화면 이벤트 추적 강화) + API/로그 표준화 작업 요약

**작성일**: 2026-01-20  
**목적**: 운영 수준의 사용자 행동 추적(Tracking) + 권한 제어(RBAC) + 통합 모니터링 완성

---

## ✅ 완료 사항

### 1) DB 스키마 변경 (Flyway Migration)

**V16__extend_com_resources_for_tracking.sql**:
- `com_resources` 테이블에 다음 컬럼 추가:
  - `resource_category` VARCHAR(50): 대분류 (MENU/UI_COMPONENT)
  - `resource_kind` VARCHAR(50): 세부 분류 (MENU_GROUP/PAGE/BUTTON/TAB/SELECT/FILTER/SEARCH/TABLE_ACTION/DOWNLOAD/UPLOAD/MODAL/API_ACTION)
  - `event_key` VARCHAR(120): 이벤트 추적 표준 키
  - `event_actions` JSONB: 허용되는 action 목록
  - `tracking_enabled` BOOLEAN: 이벤트 추적 활성화 여부
  - `ui_scope` VARCHAR(30): 적용 범위 (GLOBAL/MENU/PAGE/COMPONENT)
- 기존 13건 데이터 무손실 마이그레이션 완료

**V17__add_tenant_id_to_sys_codes.sql**:
- `sys_codes` 테이블에 `tenant_id` 컬럼 추가
- 테넌트별 커스텀 코드 지원

**V18__seed_resource_tracking_codes.sql**:
- 코드 그룹 추가: RESOURCE_CATEGORY, RESOURCE_KIND, UI_ACTION
- 코드 seed: RESOURCE_CATEGORY (2개), RESOURCE_KIND (12개), UI_ACTION (10개)

**V19__add_resource_kind_to_event_logs.sql**:
- `sys_event_logs` 테이블에 `resource_kind` 컬럼 추가

---

### 2) Entity/DTO 확장

**Resource.java**:
- 새 컬럼 필드 추가 (resourceCategory, resourceKind, eventKey, eventActions, trackingEnabled, uiScope)

**Code.java**:
- tenantId 필드 추가

**PermissionDTO.java**:
- 확장 필드 추가 (resourceCategory, resourceKind, eventKey, trackingEnabled, eventActions, meta)

**MenuNode.java**:
- 확장 필드 추가 (resourceKind, trackingEnabled)

**EventLog.java**:
- resourceKind 필드 추가

---

### 3) API 확장

**AuthService.getMyPermissions()**:
- PermissionDTO에 확장 필드 포함 (resourceCategory, resourceKind, eventKey, trackingEnabled, eventActions, meta)

**MenuService.getMenuTree()**:
- MenuNode에 resourceKind, trackingEnabled 포함

**MonitoringCollectService.recordEvent()** (고도화):
- com_resource 기반 유효성 검증
- tracking_enabled=false 이면 silent ignore
- resource_kind에 따라 action validation 수행
- sys_event_logs에 표준화된 action + resource_kind 저장

---

### 4) Repository 확장

**ResourceRepository**:
- `findByTenantIdAndKey()` 메서드 추가 (타입 무관 조회)

**CodeRepository**:
- `findByGroupKeyAndTenantIdOrderBySortOrderAsc()` 메서드 추가 (tenant_id 고려)

**CodeUsageService**:
- tenant_id를 고려한 코드 조회로 업데이트

---

### 5) 테스트 작성

**MonitoringCollectServiceTest**:
- 이벤트 수집 시 resourceKind 저장 확인
- tracking_enabled=false 인 리소스는 silent ignore 확인
- 리소스가 없으면 silent ignore 확인
- resourceKey 누락 시 예외 발생 확인

---

### 6) 문서 업데이트

**ADMIN_MONITORING_API_SPEC.md**:
- 핵심 정책 10줄 추가 (resourceCategory/resourceKind 기반 표준화, UI_ACTION 코드 기준, com_resource.event_actions 검증 등)

---

## 📋 주요 변경 파일

### Migration Files
- `V16__extend_com_resources_for_tracking.sql`
- `V17__add_tenant_id_to_sys_codes.sql`
- `V18__seed_resource_tracking_codes.sql`
- `V19__add_resource_kind_to_event_logs.sql`

### Entity Files
- `Resource.java`
- `Code.java`
- `EventLog.java`

### DTO Files
- `PermissionDTO.java`
- `MenuNode.java`

### Service Files
- `AuthService.java`
- `MenuService.java`
- `MonitoringCollectService.java`
- `CodeUsageService.java`

### Repository Files
- `ResourceRepository.java`
- `CodeRepository.java`

### Test Files
- `MonitoringCollectServiceTest.java`

### Documentation Files
- `ADMIN_MONITORING_API_SPEC.md`
- `BE_P1-5_RESOURCE_TRACKING_ENHANCEMENT_SUMMARY.md` (본 문서)

---

## ✅ 통과 조건 확인

- ✅ 기존 데이터 13건 무손실 마이그레이션 완료
- ✅ 기존 MENU 트리/권한 조회 API 호환 유지
- ✅ 타입/코드 하드코딩 제거 (CodeResolver 적용)
- ✅ 이벤트 추적 정합성 확보 (com_resource 기반 검증)
- ✅ sys_codes tenant_id 지원
- ✅ 테스트 작성 완료
- ✅ 문서 업데이트 완료

---

## 🔍 주요 개선 사항

### 1) com_resource 세분화
- 기존: MENU/UI_COMPONENT 2개 타입만
- 개선: resourceCategory + resourceKind로 세분화 (12가지 resourceKind 지원)

### 2) 이벤트 추적 표준화
- 기존: 프론트에서 임의로 eventType/action 구성
- 개선: com_resource.event_actions 기반 유효성 검증, UI_ACTION 코드 기준 표준화

### 3) 테넌트별 코드 지원
- sys_codes에 tenant_id 추가하여 테넌트별 커스텀 코드 지원

### 4) 추적성 강화
- sys_event_logs에 resource_kind 저장
- tracking_enabled로 추적 제어 가능

---

**작업 완료일**: 2026-01-20  
**작성자**: DWP Backend Team
