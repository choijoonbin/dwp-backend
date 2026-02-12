# 데모 Generate API 호출 시 테스트 데이터 적재 테이블

`POST /api/demo/generate` (또는 `/api/synapse/demo/generate`) 호출 시 **데이터가 적재되는 테이블**을 흐름 순으로 정리합니다.  
모든 테이블은 스키마 **`dwp_aura`** 에 있습니다.

---

## 1. 직렬 요청 처리 (동기)

### 1.1 전표 데이터 (DemoViolationService)

| 스키마.테이블 | 설명 | 호출당 적재 |
|---------------|------|-------------|
| **dwp_aura.fi_doc_header** | 전표 헤더 (회사코드, 전표번호, 회계연도, 전표일, 입력시간, 가맹점명 등) | 요청의 `total_count`(1~10)건만큼 **1 row/건** |
| **dwp_aura.fi_doc_item** | 전표 라인 (금액, 통화, 텍스트 등) | 전표 1건당 **1 row** (헤더:아이템 = 1:1) |

- 시나리오·intensity에 따라 금액/가맹점명/결제시간(budat, cputm)이 랜덤 생성됩니다.
- 이후 **Detect**가 이 전표를 시간 윈도우로 조회해 케이스를 만듭니다.

---

## 2. Detect 비동기 실행 (DemoDetectTrigger → DetectBatchService)

Detect가 실행되면 아래 테이블에 적재/갱신됩니다.

### 2.1 Detect 실행 이력

| 스키마.테이블 | 설명 | 호출당 적재 |
|---------------|------|-------------|
| **dwp_aura.detect_run** | Detect 배치 실행 1건 (window_from/to, status, counts_json 등) | **1 row** (STARTED → COMPLETED 또는 FAILED) |

### 2.2 케이스

| 스키마.테이블 | 설명 | 호출당 적재 |
|---------------|------|-------------|
| **dwp_aura.agent_case** | 탐지된 케이스. 전표 1건당 dedup_key 기준으로 **1건 생성** 또는 **기존 건 갱신** | 위에서 생성한 **fi_doc_header 건수만큼** (신규면 insert, 기존 동일 dedup_key면 update) |

- `fi_doc_header`를 시간 윈도우로 조회한 결과만 케이스로 올라갑니다.
- `fi_open_item`이 윈도우에 걸리면 open item 기준 케이스도 추가될 수 있습니다 (데모 시나리오에서는 보통 fi_doc만 해당).

### 2.3 감사 로그

| 스키마.테이블 | 설명 | 호출당 적재 |
|---------------|------|-------------|
| **dwp_aura.audit_event_log** | Detect/케이스 이벤트 감사 로그 | RUN_DETECT_STARTED 1건, RUN_DETECT_COMPLETED(또는 FAILED) 1건, CASE_CREATED/CASE_UPDATED는 케이스 건수만큼 |

---

## 3. Aura 분석 자동 트리거 (선택, Authorization·X-User-ID 있을 때)

케이스 생성 후 **케이스별로** Aura Thought Chain 분석이 자동 호출되며, Aura 콜백 시 아래 테이블에 적재됩니다.

### 3.1 분석 런

| 스키마.테이블 | 설명 | 호출당 적재 |
|---------------|------|-------------|
| **dwp_aura.case_analysis_run** | 케이스별 분석 실행 1건 (run_id, status, requested_by 등) | **케이스 1건당 1 row** |

### 3.2 Aura 콜백으로 적재되는 테이블

| 스키마.테이블 | 설명 | 호출당 적재 |
|---------------|------|-------------|
| **dwp_aura.case_analysis_result** | 분석 최종 결과 (risk_score, violation_clause, reasoning_summary 등) | 케이스당 **1 row** (Aura finalResult 콜백 시) |
| **dwp_aura.case_action_proposal** | 액션 제안 (타입, 위험도, rationale, payload 등) | Aura가 제안한 건수만큼 (0~N) |

- 사용자가 제안을 “실행”하면 **dwp_aura.case_action_execution** 에 실행 이력이 추가됩니다 (Generate 호출 직후 자동 적재는 아님).

---

## 4. 요약 (Generate 1회 호출, total_count=1, 케이스 1건 생성·분석까지 가정)

| 순서 | 스키마.테이블 | 적재 내용 |
|------|----------------|-----------|
| 1 | dwp_aura.fi_doc_header | 전표 헤더 1건 |
| 2 | dwp_aura.fi_doc_item | 전표 라인 1건 |
| 3 | dwp_aura.detect_run | Detect 실행 1건 |
| 4 | dwp_aura.agent_case | 케이스 1건 (신규 또는 갱신) |
| 5 | dwp_aura.audit_event_log | RUN_DETECT_* 2건 + CASE_* 1건 |
| 6 | dwp_aura.case_analysis_run | 분석 런 1건 (Authorization 있을 때) |
| 7 | dwp_aura.case_analysis_result | 분석 결과 1건 (Aura 콜백 후) |
| 8 | dwp_aura.case_action_proposal | 제안 0~N건 (Aura 콜백 후) |

- **fi_open_item**: 데모 Generate는 **fi_doc_header/fi_doc_item** 만 생성합니다. fi_open_item 은 별도 프로세스에서 채워지며, 해당 윈도우에 있으면 Detect 시 **agent_case** 에 추가로 반영될 수 있습니다.
