# 공통 Filter DTO 표준 — 백엔드 확인 응답

> job.txt 프론트 전달사항(백엔드 확인 요청)에 대한 Synapse 응답  
> 작성: 2026-02-04

---

## 1. Cases API — status (TRIAGE alias)

**FE 요청**: `status=OPEN,TRIAGE`로 통일 가능한지 확인

**답변**: ✅ **지원합니다.**

- `status=OPEN,TRIAGE` 사용 가능
- BE에서 `TRIAGE`를 내부적으로 `TRIAGED`로 매핑하여 조회 (DB enum은 TRIAGED)
- app_codes에 `TRIAGE` 정의됨 (TRIAGED와 동일 의미)

→ FE는 `status=OPEN,TRIAGE` 사용해도 됩니다.

---

## 2. Actions API — status (PENDING)

**FE 요청**: `status=PENDING` 지원 여부, `PENDING_APPROVAL` alias 필요 여부

**답변**: ✅ **지원합니다.**

- `status=PENDING` 직접 지원 (app_codes ACTION_STATUS에 PENDING 정의)
- `status=PENDING_APPROVAL`도 지원
- "View All Pending Actions": `status=PENDING&requiresApproval=true&range=24h` 사용 가능

→ 별도 alias/매핑 불필요.

---

## 3. Cases API — approvalState

**FE 요청**: `approvalState?: REQUIRES_REVIEW | NONE` 지원 여부

**답변**: ✅ **지원합니다.**

- `approvalState=REQUIRES_REVIEW` → 승인 검토 필요 케이스만 필터 (hasPendingAction=true)
- `approvalState=NONE` → 승인 검토 불필요 케이스만 필터
- Action Required용 drill-down에 사용

---

## 4. Audit API — category

**FE 요청**: `category=UI,ADMIN,ACTION,AGENT` 지원 여부

**답변**: ✅ **지원합니다.**

- `category` 또는 `eventCategory` 파라미터 사용
- 다중값 쉼표 구분: `category=UI,ADMIN,ACTION,AGENT`
- View Full Audit Log: `GET /api/synapse/audit/events?category=UI,ADMIN,ACTION,AGENT&range=24h` 사용 가능

---

## 5. 공통 파라미터

| 파라미터 | Cases | Anomalies | Actions | Audit |
|----------|-------|-----------|---------|-------|
| range | ✅ | ✅ | ✅ | ✅ |
| from, to | ✅ | ✅ | ✅ | ✅ |
| severity | ✅ | ✅ | ✅ | - |
| status | ✅ | ✅ | ✅ | - |
| q | ✅ | ✅ | ✅ | ✅ |
| page (0-based) | ✅ | ✅ | ✅ | ✅ |
| size | ✅ | ✅ | ✅ | ✅ |
| sort | ✅ | ✅ | ✅ | ✅ |

**주의**: range와 from/to 동시 제공 시 400 반환. 둘 중 하나만 사용.

---

## 6. 요약

| 항목 | 상태 |
|------|------|
| status=OPEN,TRIAGE | ✅ 지원 |
| status=PENDING (Actions) | ✅ 지원 |
| approvalState | ✅ 지원 |
| category=UI,ADMIN,ACTION,AGENT | ✅ 지원 |
| 공통 파라미터 | ✅ 3 리스트 + Audit 공통 |

→ FE 요청사항 모두 BE에서 지원 중입니다. 추가 수정 없이 연동 가능합니다.
