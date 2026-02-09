# [SynapseX Phase A] 감사추적로그 필터 코드화 + 케이스 단위 감사조회 + 상태변경 Audit 정책 — Backend 결과

## 1) Audit Filter용 코드 그룹 (완료)

### 시드 그룹

- `AUDIT_CATEGORY` — event_category
- `AUDIT_EVENT_TYPE` — event_type
- `AUDIT_OUTCOME` — outcome
- `AUDIT_ACTOR_TYPE` — actor_type
- `AUDIT_SEVERITY` — severity
- `AUDIT_RESOURCE_TYPE` — resource_type

### FE 조회 API

- `GET /api/admin/codes?groupKey=AUDIT_CATEGORY` (예)
- `GET /api/admin/codes/usage?resourceKey=menu.admin.audit` (메뉴별 일괄)

### 시드 데이터

- Flyway: `dwp-auth-server` V37__audit_filter_codes.sql
- name_ko, name_en 지원 (i18n)

---

## 2) Audit 조회 API (보강 완료)

### 경로

- `GET /api/synapse/audit/events`
- `GET /api/synapse/audit/logs` (동일)

### 필터 파라미터

| 파라미터 | 타입 | 기본 | 설명 |
|----------|------|------|------|
| from, dateFrom | datetime | - | 시작일시 |
| to, dateTo | datetime | - | 종료일시 |
| range | string | 24h | 1h\|6h\|24h\|7d\|30d\|90d. from/to 미입력 시 적용 |
| category, eventCategory | string | - | CASE, ACTION, AUDIT 등 |
| type, eventType | string | - | STATUS_CHANGE, CASE_VIEW_LIST 등 |
| outcome | string | - | SUCCESS, FAILED, DENIED |
| actorType | string | - | HUMAN, AGENT, SYSTEM |
| actorUserId | long | - | 행위자 user_id |
| resourceType | string | - | AGENT_CASE, DETECT_RUN 등 |
| resourceId | string | - | 리소스 ID |
| **caseId** | long | - | 케이스 관련 이벤트만 (신규) |
| runId | long | - | tags.runId |
| traceId | string | - | 추적 ID |
| gatewayRequestId | string | - | 게이트웨이 요청 ID |
| q | string | - | 통합 검색 (resource_id, actor_user_id, trace_id, gateway_request_id, span_id, actor_agent_id) |
| page, size | int | 0, 20 | 페이징 |
| sort | string | createdAt | 정렬 필드 |

### 케이스 단위 감사 API (신규)

`GET /api/synapse/cases/{caseId}/audit-events?page=0&size=20`

- 케이스 상세 '감사 스트림' 탭용
- 반환: auditId, createdAt, eventCategory, eventType, outcome, severity, actorType, actorDisplayName, resourceType, resourceId

---

## 3) 상태 변경 Audit 정책 (Phase A 고정)

### audit_event_log 1건 기록

- event_type: `STATUS_CHANGE`
- resource_type: `AGENT_CASE`
- resource_id: caseId
- outcome: SUCCESS 또는 FAILURE
- severity: INFO
- before_json / after_json / diff_json: 모두 채움 (status 변경 포함)

### Phase A 버튼 → 상태 매핑

| 버튼 | → 상태 |
|------|--------|
| 해결 처리 | RESOLVED |
| 무시 처리 | DISMISSED |
| 정보요청 | 정책 결정 (TRIAGED 또는 IN_PROGRESS 유지 등) |

---

## 4) 테스트 체크리스트

- [x] GET /api/admin/codes?groupKey=AUDIT_CATEGORY 등 정상 반환
- [x] Audit 조회 API에서 q=85114 또는 caseId 필터로 조회 가능
- [x] 케이스 상태 변경 시 audit_event_log에 STATUS_CHANGE 1건 생성 + before/after/diff 채움
- [x] GET /api/synapse/cases/{caseId}/audit-events 정상 반환
