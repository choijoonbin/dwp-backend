# 스크리닝 → 분석 Run 스크리닝 결과 전달 계약 (BE ↔ Aura)

## 개요

- Aura는 **스크리닝(screen-batch)** 결과와 **케이스 분석(analysis run)** 결과를 별도 API로 처리합니다.
- 분석 시 **스크리닝에서 판단한 caseType·reasonText**를 전달받지 않으면 기본 위험 유형(예: DUPLICATE_INVOICE)으로 fallback하여 reasonText를 생성합니다.
- BE는 스크리닝 결과를 persist한 뒤 **분석 run 요청 body_evidence** 및 **get_case 응답**에 포함해 전달합니다.

---

## 1. 분석 run 요청 — body_evidence

**API**: `POST /aura/cases/{caseId}/analysis-runs` (AuraCaseTabClient.triggerAnalyze)

**요청 body 내 `body_evidence`**에 아래 필드를 포함합니다.

| 필드 | JSON 키 | 타입 | 필수 | 설명 |
|------|--------|------|------|------|
| doc_id | `doc_id` | string | - | 문서 식별자 (BUKRS-BELNR-GJAHR 또는 BELNR) |
| item_id | `item_id` | string | - | 항목 식별자 (BUZEI) |
| case_type | `case_type` | string | 권장 | 스크리닝 6종 중 하나. 예: HOLIDAY_USAGE, DUPLICATE_SUSPECT, SPLIT_PAYMENT, PRIVATE_USE_RISK, LIMIT_EXCEED, UNUSUAL_PATTERN |
| screening_reason_text | `screening_reason_text` | string | 권장 | 스크리닝 판단 요약. 예: "제9조(공휴일·휴무일 사용 제한)에 따라..." |

- Aura는 **camelCase**(`caseType`, `reasonText`)도 인식합니다. BE는 snake_case(`case_type`, `screening_reason_text`)로 직렬화합니다.
- 구현: `BodyEvidenceDto` + `CaseAnalysisService.buildBodyEvidence(AgentCase)`에서 `agent_case.case_type`, `agent_case.reason_text` 설정.

**예시 (BE → Aura 전송 형식)**

```json
{
  "body_evidence": {
    "doc_id": "1000-5105012345-2025",
    "item_id": "001",
    "case_type": "HOLIDAY_USAGE",
    "screening_reason_text": "제9조(공휴일·휴무일 사용 제한)에 따라, hrStatus가 LEAVE인 상태에서의 결제로 위반 의심."
  }
}
```

---

## 2. get_case 응답 — 스크리닝 결과 포함

**API**: `GET /api/synapse/agent-tools/cases/{caseId}` (AgentToolController.getCase → CaseDetailDto)

응답 최상위에 다음 필드를 포함합니다.

| 필드 | JSON 키 (기본) | JsonAlias | 설명 |
|------|-----------------|-----------|------|
| caseType | `caseType` | `case_type` | 스크리닝 caseType (6종 중 하나) |
| reasonText | `reasonText` | `screening_reason_text` | 스크리닝 판단 요약 문장 |

- 구현: `CaseDetailDto`에 `caseType`, `reasonText` 정의, `CaseQueryService.buildCaseDetail`에서 `agent_case.case_type`, `agent_case.reason_text` 매핑.
- body_evidence에 값이 없을 때 Aura가 get_case 응답을 보조로 사용할 수 있습니다.

---

## 3. evidence 스냅샷 (분석 run 시 evidence 필드)

분석 run 요청의 **evidence** (JsonNode)에도 최상위에 동일 값을 넣습니다.

- `caseType` (string)
- `reasonText` (string)
- `screening_reason_text` (string, reasonText와 동일)

구현: `CaseAnalysisService.buildEvidenceSnapshot(AgentCase)`에서 snapshot에 설정.

---

## 4. 스크리닝 결과의 출처

- **POST /aura/detect/screen-batch** 응답의 케이스별 `caseType`, `reasonText`(및 severity, score)를
- `DetectBatchService`에서 해당 케이스 저장 시 `agent_case.case_type`, `agent_case.reason_text` 등으로 persist하고,
- 이후 **분석 run body_evidence** 및 **get_case 응답**에 위 필드명으로 담아 전달합니다.

---

## 5. 검증 방법

**Aura 로그 (정상 반영 시)**

- `audit_analysis body_evidence: case_id=... evidence_caseType=HOLIDAY_USAGE has_reasonText=True`
- `audit_analysis: using screening caseType from get_case/evidence case_id=... caseType=HOLIDAY_USAGE`

**Aura 로그 (미전달 시)**

- `evidence_caseType=None has_reasonText=False`
- `risk_type from case_data fallback ... risk_type=DUPLICATE_INVOICE (no screening_case_type)`

**BE 로그 (INFO — 스크리닝 전달 검증용)**

- get_case: `get_case screening result: caseId={} caseType={} reasonTextIncluded={}`
- evidence 스냅샷: `screening result in evidence snapshot: caseId={} caseType={} reasonTextIncluded={}`
- 분석 run Phase2: `analysis run sending to Aura (Phase2): ... body_evidence: case_type={} reasonTextIncluded={} evidence: caseType={} reasonTextIncluded={}`
- 분석 run Phase3: `analysis run sending to Aura (Phase3): ... screening: caseType={} reasonTextIncluded={}`

**BE 로그 (DEBUG)**

- `logging.level.com.dwp.services.synapsex=DEBUG` 설정 시:
  - 분석 run: `Aura analyze body_evidence: caseId=... runId=... docId=... itemId=... caseType=... reasonText=...`
  - evidence 스냅샷: `analysis run evidence snapshot caseId=... caseType=... reasonText=...`
  - get_case: `get_case response caseId=... caseType=... reasonText=...`

---

## 6. BE 구현 요약

| 구분 | 클래스/메서드 | 비고 |
|------|----------------|------|
| body_evidence DTO | `BodyEvidenceDto` | doc_id, item_id, case_type, screening_reason_text |
| body_evidence 채우기 | `CaseAnalysisService.buildBodyEvidence(AgentCase)` | agent_case.case_type, reason_text 사용 |
| get_case 응답 | `CaseDetailDto` (caseType, reasonText) | CaseQueryService.buildCaseDetail |
| evidence 스냅샷 | `CaseAnalysisService.buildEvidenceSnapshot(AgentCase)` | caseType, reasonText, screening_reason_text |
| 스크리닝 persist | `DetectBatchService` | screen-batch 응답 → agent_case.case_type, reason_text |

---

*문서 기준: Aura 분석 Run 시 스크리닝 결과 전달 요청사항 반영 (BE 구현 완료).*
