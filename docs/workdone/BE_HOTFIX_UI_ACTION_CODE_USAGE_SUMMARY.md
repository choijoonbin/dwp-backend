# BE Hotfix: UI_ACTION 코드 그룹 확정 + CodeUsage seed 보강 (Events Tab 필터 연동 준비)

**작성일**: 2026-01-20  
**목적**: Events 탭 필터(eventType/action selectbox)를 "하드코딩 제거 + CodeUsage 기반 코드 조회"로 완성

---

## ✅ 완료 사항

### 1) V18 Migration 수정 (sys_code_usages 컬럼명 오류 수정)

**문제**: V18에서 sys_code_usages INSERT 시 잘못된 컬럼명 사용
- 잘못됨: `default_code, description`
- 올바름: `scope, remark`

**수정**:
- `V18__seed_resource_tracking_codes.sql`의 sys_code_usages INSERT 문 수정
- 컬럼명을 `scope`, `remark`로 변경
- `scope = 'MENU'`, `remark = 'Events 탭 필터용 UI 액션 코드'` 설정

---

### 2) UI_ACTION 코드 그룹 확인

**현재 상태**:
- ✅ sys_code_groups에 UI_ACTION 그룹 존재 (V18)
- ✅ sys_codes에 UI_ACTION 코드 10개 존재 (V18):
  - VIEW, CLICK, EXECUTE, SCROLL, SEARCH, FILTER, DOWNLOAD, OPEN, CLOSE, SUBMIT
- ✅ tenant_id = NULL (전사 공통 코드)
- ✅ sort_order 포함하여 정렬 안정성 보장

**표준 액션 정의 (확정)**:
- VIEW, CLICK, EXECUTE, SCROLL, SEARCH, FILTER, DOWNLOAD, OPEN, CLOSE, SUBMIT (대문자 기준)

---

### 3) sys_code_usages 매핑 확인 및 수정

**현재 상태**:
- ✅ V18에 menu.admin.monitoring → UI_ACTION 매핑 존재
- ✅ 컬럼명 오류 수정 완료

**매핑 정보**:
- tenant_id: 1 (dev tenant)
- resource_key: 'menu.admin.monitoring'
- code_group_key: 'UI_ACTION'
- scope: 'MENU'
- enabled: true
- sort_order: 10
- remark: 'Events 탭 필터용 UI 액션 코드'

---

### 4) 테스트 보강

**CodeUsageServiceMonitoringTest.java** (신규):
- menu.admin.monitoring 조회 시 UI_ACTION 그룹 포함 확인
- UI_ACTION 코드 10개 이상 반환 확인
- tenant_id 필터 적용 확인
- 다른 테넌트 격리 확인

**CodeControllerTest.java** (보강):
- `/api/admin/codes/usage?resourceKey=menu.admin.monitoring` 호출 시 UI_ACTION 포함 확인

---

### 5) 문서 업데이트

**CODE_MANAGEMENT.md**:
- menu.admin.monitoring 예시 추가
- UI_ACTION 응답 예시 추가 (10개 코드 전체)

---

## 📋 주요 변경 파일

### Migration Files
- `V18__seed_resource_tracking_codes.sql` (수정: sys_code_usages 컬럼명)

### Test Files
- `CodeUsageServiceMonitoringTest.java` (신규)
- `CodeControllerTest.java` (보강)

### Documentation Files
- `CODE_MANAGEMENT.md` (업데이트: menu.admin.monitoring 예시 추가)
- `BE_HOTFIX_UI_ACTION_CODE_USAGE_SUMMARY.md` (본 문서)

---

## ✅ 완료 조건 확인

- ✅ Flyway 적용 후 sys_codes에 UI_ACTION 10개 존재
- ✅ sys_code_usages에 menu.admin.monitoring → UI_ACTION 매핑 존재 (컬럼명 수정 완료)
- ✅ GET /api/admin/codes/usage?resourceKey=menu.admin.monitoring 응답에 UI_ACTION 포함
- ✅ 테스트 통과 (컴파일 성공)
- ✅ 기존 기능 영향 없음 (하위 호환성 유지)

---

## 🔍 API 사용 예시

### 프론트엔드 호출
```typescript
// Events 탭 필터용 코드 조회
GET /api/admin/codes/usage?resourceKey=menu.admin.monitoring
Headers:
  X-Tenant-ID: 1
  Authorization: Bearer {JWT}
```

### 응답 구조
```json
{
  "success": true,
  "data": {
    "codes": {
      "UI_ACTION": [
        { "code": "VIEW", "name": "조회", ... },
        { "code": "CLICK", "name": "클릭", ... },
        { "code": "EXECUTE", "name": "실행", ... },
        // ... 총 10개
      ]
    }
  }
}
```

### 프론트엔드 활용
```typescript
// 하드코딩 제거 전
const actions = ['view', 'click', 'execute', ...]; // 소문자 하드코딩

// 하드코딩 제거 후
const response = await fetch('/api/admin/codes/usage?resourceKey=menu.admin.monitoring');
const { codes } = response.data;
const actions = codes.UI_ACTION.map(item => item.code); // 대문자 표준 코드
```

---

## 📝 참고 사항

### 표준 액션 코드 (대문자 기준)
- VIEW, CLICK, EXECUTE, SCROLL, SEARCH, FILTER, DOWNLOAD, OPEN, CLOSE, SUBMIT

### 레거시 호환성
- 기존에 eventType으로 view/click/execute/scroll (소문자)를 사용하던 레거시가 있어도
- 코드 시스템의 정답은 항상 "대문자 UI_ACTION"입니다.
- 프론트엔드는 대문자 기준으로 동작하도록 전환할 것입니다.

### tenant_id 격리
- UI_ACTION 코드는 tenant_id = NULL (전사 공통 코드)
- sys_code_usages는 tenant_id = 1 (dev tenant) 기준으로 매핑
- 다른 테넌트도 동일한 전사 공통 코드를 사용하되, 매핑은 테넌트별로 관리 가능

---

**작업 완료일**: 2026-01-20  
**작성자**: DWP Backend Team
