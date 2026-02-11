# Case Status Filter & Transition Logic — 수정 결과

## 1. Query 수정 (GET /api/synapse/cases)

### 1.1 상태(status) 조건
- **기존**: `status` 파라미터가 없으면 **상태 조건을 넣지 않음** (모든 상태 조회).  
  `status`가 있으면 단일/복수(쉼표 구분)로 필터.
- **변경**: 동작 유지. 주석만 보강:  
  `"status 미전달 시 상태 필터 미적용 (모든 상태 조회). 전달 시 OPEN, IN_PROGRESS 등 복수 지원(쉼표 구분)."`
- **결과**: 조회 조건을 넘기지 않으면 **status 조건 없이** 조회.  
  `status=OPEN` 또는 `status=OPEN,IN_PROGRESS` 등으로 필터 시 OPEN·IN_PROGRESS 포함 가능.

### 1.2 날짜/시간 조건
- **기존**: `range`/`from`/`to`/`dateFrom`/`dateTo`/`detectedFrom`/`detectedTo`를 하나도 안 넘겨도  
  `DrillDownParamUtil.resolve`로 **기본 24h**가 적용되어 `dateFrom`/`dateTo`가 설정됨.
- **변경**: **시간 관련 파라미터가 하나도 없을 때**는 `dateFrom`/`dateTo`/`detectedFrom`/`detectedTo`를 **null**로 두어,  
  `findCases`에서 날짜 조건을 **전혀 적용하지 않음** (전 기간 조회).
- **적용 위치**: `CaseController.getCases()`  
  - `hasTimeFilter`: `range`, `from`, `to`, `dateFrom`, `dateTo`, `detectedFrom`, `detectedTo` 중 하나라도 있으면 true.  
  - `hasTimeFilter == false`이면 `dateFrom`/`dateTo`/`detectedFrom`/`detectedTo`를 null로 설정.

**요약**:  
- **조회 조건을 넘기지 않으면** → status 조건 없음 + 날짜/시간 조건 없음 → **전체 케이스 조회**.  
- **status만 넘기면** → 해당 상태만 필터, 날짜는 조건 없음.  
- **날짜/range만 넘기면** → 기존처럼 24h 등 적용.

---

## 2. State Transition (상세 조회 시 OPEN → IN_PROGRESS)

### 2.1 명시적 API (기존)
- **POST /api/synapse/cases/{caseId}/status**  
  - Body: `{"status": "IN_PROGRESS"}`  
  - `CaseCommandService.updateCaseStatus()` 호출, 감사 로그 기록.

### 2.2 자동 전이 (추가)
- **GET /api/synapse/cases/{caseId}** 호출 시:
  1. **CaseCommandService.ensureInProgressWhenOpen(tenantId, caseId, actorUserId, ip, userAgent, gatewayRequestId)** 호출.
  2. 해당 케이스가 **OPEN**일 때만 **IN_PROGRESS**로 변경 후 저장.
  3. `auditWriter.logCaseStatusChange(...)` 로 상태 변경 감사 로그 기록.
  4. 이어서 `findCaseDetail()`로 상세 조회 → 응답에는 이미 **IN_PROGRESS**로 반영됨.

**적용 코드**:
- `CaseCommandService.ensureInProgressWhenOpen(...)` 신규 메서드.
- `CaseController.getCaseDetail()` 내, `findCaseDetail()` 호출 **직전**에 `ensureInProgressWhenOpen` 호출.

---

## 3. 수정된 서비스 로직 요약

| 구분 | 파일 | 내용 |
|------|------|------|
| 목록 조회 (조건 없음) | CaseController.getCases | `hasTimeFilter` 도입, 시간 파라미터 없으면 dateFrom/dateTo/detectedFrom/detectedTo = null |
| 목록 조회 (status) | CaseQueryService.findCases | 주석 보강만 (status 미전달 시 조건 미적용은 기존 동작) |
| 상세 조회 전이 | CaseController.getCaseDetail | getCaseDetail 진입 시 `ensureInProgressWhenOpen` 호출 |
| 자동 전이 로직 | CaseCommandService | `ensureInProgressWhenOpen` 추가 (OPEN일 때만 IN_PROGRESS로 변경 + 감사) |

---

## 4. 동작 정리

- **GET /api/synapse/cases** (파라미터 없음):  
  **status/날짜 조건 없이** 전체 케이스 조회 (OPEN, IN_PROGRESS, 기타 모두 포함).
- **GET /api/synapse/cases?status=OPEN,IN_PROGRESS**:  
  OPEN·IN_PROGRESS만 조회.
- **GET /api/synapse/cases/{id}**:  
  조회 시 해당 케이스가 OPEN이면 **자동으로 IN_PROGRESS**로 변경 후 상세 반환.  
  명시적 변경은 **POST /api/synapse/cases/{id}/status** 계속 사용.
