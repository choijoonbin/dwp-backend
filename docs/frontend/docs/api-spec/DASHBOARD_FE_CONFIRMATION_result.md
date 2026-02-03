# Dashboard API FE 확인 요청 — 응답

> FE 확인 요청 5건에 대한 BE 응답  
> 최종 업데이트: 2026-01-29

---

## 1. 경로 prefix (/synapse)

**질문**: BE에서 /synapse를 포함한 경로를 내려줄 예정인가요, 아니면 FE에서 prefix를 붙이는 방식이 맞나요?

**응답**: **FE에서 prefix를 붙이는 방식이 맞습니다.**

- BE는 `/cases`, `/audit`, `/actions` 등 **prefix 없이** 경로를 반환합니다.
- FE의 `normalizePath()`로 `/synapse`를 붙여 `/synapse/cases`, `/synapse/audit` 등으로 사용하는 현재 방식 유지하면 됩니다.

---

## 2. Team Snapshot – Pending Approvals 링크 (links.actionsPath)

**질문**: BE에서 links.actionsPath를 추가해 줄 수 있나요?

**응답**: **추가 완료했습니다.**

- `links.actionsPath`: `/actions?assignee={analystUserId}&status=PENDING_APPROVAL`
- 예: `/actions?assignee=11001&status=PENDING_APPROVAL`
- Pending Approvals 셀 클릭 시 이 경로를 사용하면 됩니다.

---

## 3. Top Risk Drivers – anomaliesPath vs casesPath

**질문**: 리스크 드라이버 클릭 시 이동 대상이 /cases?caseType=...인가요, 아니면 /anomalies?type=...인가요?

**응답**: **`/cases?caseType={key}&status=OPEN`이 맞습니다.**

- Top Risk Drivers는 `agent_case`를 `case_type`별로 집계한 데이터입니다.
- drill-down 대상은 **케이스 목록**이므로 `/cases?caseType=DUPLICATE_INVOICE&status=OPEN` 형태를 사용합니다.
- `links.anomaliesPath` 필드명은 유지하되, 실제 값은 cases 경로입니다. (FE에서 fallback 시 `/cases?caseType=...` 사용 권장)

---

## 4. Agent Activity – casePath의 ID 형식

**질문**: casePath의 123은 DB ID(resourceId)인가요, 아니면 caseNumber(예: CS-2026-0001)인가요?

**응답**: **DB ID (case_id)입니다.**

- `casePath`: `/cases/123` → `123`은 `agent_case.case_id` (DB PK)
- `resourceId`: `"123"` (동일 값)
- `caseId`: `"CS-2026-0001"` (표시용 caseNumber)
- FE 라우팅 `/synapse/cases/:id`의 `:id`에는 **숫자형 case_id**를 사용하면 됩니다.

---

## 5. Audit API – sort 파라미터

**질문**: sort 파라미터(예: sort=created_at,desc)는 지원하나요?

**응답**: **지원합니다. `sort=createdAt,desc` 형식을 사용해 주세요.**

- Spring Data Pageable 기준으로 `sort` 파라미터를 지원합니다.
- **속성명은 camelCase**를 사용합니다: `createdAt` (snake_case `created_at`은 미지원)
- 예: `?sort=createdAt,desc`
- 복수 정렬: `?sort=createdAt,desc&sort=auditId,asc`

---

## 변경 사항 요약

| 항목 | 변경 |
|------|------|
| Team Snapshot | `links.actionsPath` 추가 |
| 경로 prefix | FE에서 `/synapse` 추가 (기존 방식 유지) |
| Top Risk Drivers | `/cases?caseType=...` 사용 |
| Agent Activity | casePath는 DB case_id 사용 |
| Audit API | `sort=createdAt,desc` 지원 |
