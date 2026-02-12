# 프론트엔드 수정 요청: 데모 시나리오 생성 API 호출 규격

아래 규격에 맞게 **POST /api/demo/generate** (또는 **POST /api/synapse/demo/generate**) 호출을 수정해 주세요.

---

## 1. 엔드포인트

| 항목 | 값 |
|------|-----|
| **Method** | `POST` |
| **URL** | `{GATEWAY_BASE}/api/demo/generate` 또는 `{GATEWAY_BASE}/api/synapse/demo/generate` |
| **Content-Type** | `application/json` (필수) |

---

## 2. 요청 헤더 (권장)

| 헤더 | 필수 | 설명 |
|------|------|------|
| `Authorization` | 권장 | Bearer JWT. 있으면 케이스 생성 직후 Aura 분석 자동 트리거에 사용됨. |
| `X-Tenant-ID` | 선택 | 미제공 시 테넌트 1 사용. |
| `X-User-ID` | 권장 | Aura 자동 분석 시 사용. |
| `Content-Type` | **필수** | 반드시 `application/json` 로 전송. |

---

## 3. 요청 Body (JSON)

백엔드는 **camelCase**와 **snake_case** 둘 다 수용합니다. 아래 중 하나의 형태로 보내면 됩니다.

### 3.1 백엔드가 인식하는 필드만 사용 (권장 — 오류 최소화)

```json
{
  "scenario_type": "split_payment",
  "total_count": 1,
  "intensity": "VIOLATION"
}
```

또는 camelCase:

```json
{
  "scenarioType": "split_payment",
  "count": 1,
  "intensity": "VIOLATION"
}
```

### 3.2 필드 설명

| 필드 (둘 중 하나 사용 가능) | 타입 | 필수 | 설명 | 예시 |
|----------------------------|------|------|------|------|
| `scenario_type` / `scenarioType` | string | N | 시나리오 유형. 미지정 시 `NORMAL` | `"split_payment"`, `"LATE_NIGHT"`, `"WEEKEND_MEAL"`, `"OVER_LIMIT"`, `"NORMAL"` |
| `total_count` / `count` | number | N | 생성 건수 (1~10). 미지정 시 1 | `1` |
| `intensity` | string | N | `VIOLATION` 또는 `NORMAL`. 미지정 시 시나리오에 따라 유추 | `"VIOLATION"` |
| `limit_amount_krw` / `limitAmountKrw` | number | N | 규정 한도(원). 미지정 시 30000 | `30000` |
| `amount_range_min` / `amountRangeMin` | number | N | 금액 범위 하한(원). max와 함께 지정 시 구간 랜덤 | `10000` |
| `amount_range_max` / `amountRangeMax` | number | N | 금액 범위 상한(원). min과 함께 지정 시 구간 랜덤 | `60000` |

### 3.3 사용하지 않는 필드

- **`base_currency`**  
  - 백엔드에서 사용하지 않으며, **unknown 필드는 무시**됩니다.  
  - 오류를 줄이려면 **body에서 제거**하는 것을 권장합니다.  
  - 프론트에서만 쓰려면 그대로 두어도 400 원인은 되지 않습니다.

---

## 4. 수정 체크리스트 (프론트)

1. **Content-Type**  
   - 요청 헤더에 `Content-Type: application/json` 이 반드시 포함되는지 확인.

2. **Body 필드명**  
   - `scenario_type`, `total_count`, `intensity` 만 보내도 동작합니다.  
   - `base_currency` 는 제거해도 되고, 남겨둬도 백엔드는 무시합니다.

3. **시나리오 유형 값**  
   - 허용 값: `split_payment`, `LATE_NIGHT`, `WEEKEND_MEAL`, `OVER_LIMIT`, `NORMAL`  
   - 대소문자/언더스코어는 백엔드에서 정규화합니다.

4. **권장 최소 요청 예시 (복사해서 사용 가능)**  
   ```json
   {
     "scenario_type": "split_payment",
     "total_count": 1,
     "intensity": "VIOLATION"
   }
   ```
   - 위와 같이 보내고, **헤더에 `Content-Type: application/json`** 만 꼭 넣어 주세요.

---

## 5. 참고: 현재 프론트 호출과의 차이

- 현재:  
  `{ scenario_type: "split_payment", total_count: 1, intensity: "VIOLATION", base_currency: "KRW" }`
- 백엔드는 `base_currency` 를 사용하지 않으며, `@JsonIgnoreProperties(ignoreUnknown = true)` 로 무시합니다.
- 그럼에도 400이 난다면  
  - **Content-Type: application/json** 이 빠졌는지,  
  - **Body가 빈 문자열(`""`)로 가지 않는지**,  
  - **실제 전송 JSON이 한글/특수문자 등으로 깨지지 않았는지**  
  한 번 더 확인해 주세요.

이 규격으로 수정 후에도 400이 발생하면, 응답 body의 `message`(에러 메시지)와 함께 알려 주시면 됩니다.
