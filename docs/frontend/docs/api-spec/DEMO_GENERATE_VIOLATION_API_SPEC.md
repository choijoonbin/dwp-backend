# 시연(데모) 테스트 데이터 생성 API 규약서

## 1. 시나리오 유형 코드 조회 (드롭다운용)

프론트에서 테스트 데이터 생성 시 **유형을 선택**할 수 있도록 백엔드가 관리하는 7개 코드를 조회합니다.

### GET /api/synapse/demo/scenario-types

**요청**
- Method: `GET`
- URL: `{GATEWAY_BASE}/api/synapse/demo/scenario-types` (또는 `/api/demo/scenario-types`)
- Header: `X-Tenant-ID`(선택), `Authorization`(선택)

**응답**
- `ApiResponse<List<ScenarioTypeOptionDto>>`
- 각 항목: `{ "code": "SPLIT_PAYMENT", "label": "한도 우회 분할 결제 의심" }`

| code | label |
|------|--------|
| HOLIDAY_USAGE | 휴일/심야 사적 유용 의심 |
| DUPLICATE_SUSPECT | 중복 청구 및 분할 결제 의심 |
| SPLIT_PAYMENT | 한도 우회 분할 결제 의심 |
| PRIVATE_USE_RISK | 가맹점 성격 업무 무관 |
| LIMIT_EXCEED | 지출 한도·가이드라인 초과 |
| UNUSUAL_PATTERN | 이상 거래 패턴 |
| DEFAULT | 기타 |

**사용**: FE는 이 API로 목록을 받아 드롭다운을 구성하고, 사용자가 선택한 항목의 `code`를 **POST generate-violation**의 `scenarioType`으로 전달합니다.

---

## 2. 테스트 데이터 생성 (위반/정상 시나리오)

### POST /api/synapse/demo/generate-violation

**요청**
- Method: `POST`
- URL: `{GATEWAY_BASE}/api/synapse/demo/generate-violation` (또는 `/api/demo/generate-violation`, `/api/synapse/demo/generate`)
- Header:
  - `X-Tenant-ID`: (선택) 테넌트 ID. 미지정 시 1
  - `X-User-ID`: (선택) 사용자 ID. Aura 분석 자동 트리거 시 사용
  - `Authorization`: (선택) Bearer JWT. Aura 분석 호출 시 전달
  - `Content-Type`: `application/json`
- Body (JSON, camelCase·snake_case 모두 수용):

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| scenarioType / scenario_type | string | N | 시나리오 유형. **GET /scenario-types 응답의 code 값 사용**. 미지정 시 DEFAULT |
| intensity | string | N | VIOLATION / WARNING / NORMAL. 미지정 시 시나리오에 따라 유추 |
| count / total_count | number | N | 생성 건수(1~10). **SPLIT_PAYMENT일 때는 무시**되고 2~3건 쌍이 자동 생성됨. 기본 1 |
| limitAmountKrw / limit_amount_krw | number | N | 규정 한도(원). 미지정·0 이하면 30000 |
| amountRangeMin / amount_range_min | number | N | 금액 범위 하한(원). max와 함께 지정 시 해당 구간 랜덤 |
| amountRangeMax / amount_range_max | number | N | 금액 범위 상한(원). **모든 금액은 양수로 생성됨** |

**예시 (현재 payload 호환)**
```json
{
  "scenarioType": "LATE_NIGHT",
  "count": 1,
  "intensity": "VIOLATION"
}
```

**권장 (7개 코드 사용)**
```json
{
  "scenarioType": "SPLIT_PAYMENT",
  "count": 1,
  "intensity": "VIOLATION"
}
```
- `scenarioType`: 반드시 **GET /scenario-types**에서 내려준 `code` 7개 중 하나 사용 (HOLIDAY_USAGE, DUPLICATE_SUSPECT, SPLIT_PAYMENT, PRIVATE_USE_RISK, LIMIT_EXCEED, UNUSUAL_PATTERN, DEFAULT).
- 기존 값 `LATE_NIGHT`, `WEEKEND_MEAL`, `OVER_LIMIT`, `NORMAL`은 하위 호환으로 수신 가능하며 내부적으로 위 7개 코드로 매핑됩니다.

**응답**
- `ApiResponse<GenerateViolationResponse>`
- `createdDocKeys`: 생성된 전표 docKey 배열 (예: `["1000-Dxxxxx-2025"]`)
- `createdCaseIds`: (현재 빈 배열)
- `detectRunId`, `detectRunStatus`: "ASYNC_STARTED"
- `message`: 안내 문구

**동작 요약**
1. 전표(fi_doc_header + fi_doc_item) 생성. **금액(amount/wrbtr/dmbtr)은 항상 양수**.
2. **SPLIT_PAYMENT**일 때: 동일 날짜·동일 가맹점·유사 금액 **2~3건**을 한 쌍으로 생성 (1건만 생성되는 오류 방지).
3. 데모 전표에는 `intended_risk_type`이 저장되며, Detect 시 케이스의 `evidence_json.intended_risk_type`으로 Aura에 전달되어 오판 방지.
4. Detect 비동기 실행 → 케이스 생성 → WebSocket으로 `case_created` / `analysis_started` 전달.

---

## 3. 하위 호환 매핑

| FE 전달 값 (기존) | 백엔드 처리 |
|-------------------|-------------|
| LATE_NIGHT | HOLIDAY_USAGE 동작 |
| WEEKEND_MEAL | HOLIDAY_USAGE 동작 |
| OVER_LIMIT | LIMIT_EXCEED 동작 |
| NORMAL | DEFAULT 동작 |
| split_payment (소문자) | SPLIT_PAYMENT |

FE는 신규 개발 시 **GET /scenario-types**로 코드 목록을 받아 그대로 `scenarioType`으로 보내는 것을 권장합니다.
