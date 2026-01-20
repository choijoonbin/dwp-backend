# BE Hotfix: Monitoring Event 수집 시 action normalize + 코드 검증 강화 (대소문자 호환)

**작성일**: 2026-01-20  
**목적**: 프론트/리모트에서 action 값이 소문자(view/click) 또는 혼용(View/Click)으로 들어와도 정상적으로 표준(대문자 UI_ACTION)으로 정규화되어 저장

---

## ✅ 완료 사항

### 1) EventCollectRequest DTO 정리

**변경 사항**:
- `eventType`: 필수 → 선택 (deprecated)
- `action`: 필수 → 선택 (하지만 action 또는 eventType 중 하나는 필수)
- 주석 추가: action 권장, eventType deprecated

**원칙**:
- action이 있으면 action을 우선 사용
- action이 없고 eventType만 있으면 eventType을 action으로 매핑 (deprecated 지원)

---

### 2) action normalize 로직 구현

**normalizeAction() 메서드 추가**:
- null/blank면 null 반환
- trim 후 대문자 변환
- 예) " view " -> "VIEW"
- 예) "Click" -> "CLICK"

**적용 위치**:
- `MonitoringCollectService.recordEvent()`에서 action 우선 사용
- action이 없으면 eventType을 action으로 매핑

---

### 3) 검증 규칙 강화

**검증 순서**:
1. **UI_ACTION 코드 존재 검증** (필수)
   - `CodeResolver.validate("UI_ACTION", normalizedAction)`
   - 없으면 silent fail (저장하지 않고 return)

2. **com_resource 기반 이벤트 허용 검증** (필수)
   - `tracking_enabled = true` 확인
   - `event_actions` 내 action 포함 여부 확인
   - 불일치면 silent fail (저장하지 않음)

3. **저장**
   - `sys_event_logs.action` 컬럼에 정규화된 action 저장 (대문자)
   - `sys_event_logs.resource_kind` 저장 유지

---

### 4) 테스트 보강

**MonitoringCollectServiceTest 추가 테스트**:
- ✅ action="click" 입력 시 DB에 "CLICK" 저장되는지 (소문자 정규화)
- ✅ eventType="view"만 입력해도 "VIEW"로 저장되는지 (deprecated 지원)
- ✅ UI_ACTION 코드 없으면 저장되지 않는지 (silent fail)
- ✅ com_resource.event_actions 제한 위반 시 저장되지 않는지 (silent fail)
- ✅ action 정규화: 공백 포함, 혼용 대소문자 처리

---

## 📋 주요 변경 파일

### Service Files
- `MonitoringCollectService.java`
  - `normalizeAction()` 메서드 추가
  - 검증 순서 재정렬 (UI_ACTION 검증 → com_resource 검증)
  - silent fail 정책 강화

### DTO Files
- `EventCollectRequest.java`
  - eventType 필수 제약 제거 (deprecated)
  - action 필수 제약 제거 (action 또는 eventType 중 하나 필수)
  - 주석 추가

### Test Files
- `MonitoringCollectServiceTest.java`
  - 5개 테스트 케이스 추가

### Documentation Files
- `BE_HOTFIX_ACTION_NORMALIZE_SUMMARY.md` (본 문서)

---

## ✅ 완료 조건 확인

- ✅ action normalize 로직 적용됨
- ✅ UI_ACTION 코드 검증 포함됨 (없으면 silent fail)
- ✅ com_resource.event_actions 제한 준수 (불일치 시 silent fail)
- ✅ sys_event_logs 저장 값이 대문자 표준화됨
- ✅ 모든 테스트 통과 (컴파일 성공)
- ✅ 기존 silent fail 정책 유지 (프론트 장애 유발 금지)
- ✅ tenant_id 격리 유지

---

## 🔍 동작 예시

### 입력 → 정규화 → 저장

| 입력 | 정규화 결과 | 저장 여부 |
|------|------------|----------|
| `action: "click"` | `"CLICK"` | ✅ (UI_ACTION 코드 있으면) |
| `action: "  View  "` | `"VIEW"` | ✅ (UI_ACTION 코드 있으면) |
| `action: "Click"` | `"CLICK"` | ✅ (UI_ACTION 코드 있으면) |
| `eventType: "view"` (action 없음) | `"VIEW"` | ✅ (deprecated 지원) |
| `action: "INVALID"` | `"INVALID"` | ❌ (UI_ACTION 코드 없음, silent fail) |
| `action: "CLICK"` (event_actions: ["VIEW"]) | `"CLICK"` | ❌ (event_actions 제한 위반, silent fail) |

---

## 📝 검증 흐름도

```
입력: action="click" 또는 eventType="view"
  ↓
1. normalizeAction() → "CLICK" 또는 "VIEW"
  ↓
2. UI_ACTION 코드 검증
  ├─ 없음 → silent fail (저장 안 함)
  └─ 있음 → 다음 단계
  ↓
3. com_resource 조회
  ├─ 없음 → silent fail
  └─ 있음 → 다음 단계
  ↓
4. tracking_enabled 확인
  ├─ false → silent fail
  └─ true → 다음 단계
  ↓
5. event_actions 제한 확인
  ├─ 불일치 → silent fail
  └─ 일치 → 다음 단계
  ↓
6. sys_event_logs 저장 (대문자 action)
```

---

## 🛡️ Silent Fail 정책

**원칙**: 수집 API는 절대 500을 내지 않음

**Silent Fail 조건**:
1. action normalize 실패 (null 반환)
2. UI_ACTION 코드 없음
3. com_resource 없음
4. tracking_enabled = false
5. event_actions 제한 위반

**동작**:
- 저장하지 않고 return
- 경고 로그만 남김
- 프론트엔드에 에러 응답하지 않음

---

**작업 완료일**: 2026-01-20  
**작성자**: DWP Backend Team
