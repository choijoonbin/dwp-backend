# Phase 3: Lineage Query for Document Context — 설계 및 Pre-Check

## Pre-Check (MUST ANSWER)

### 1. `resource_key` 문자열 규격이 `sap_raw_events`와 `agent_activity_log` 간에 100% 일치함을 확인했습니까?

**답변:**

- **sap_raw_events** 테이블에는 **`resource_key` 컬럼이 없습니다.**  
  컬럼: `id`, `tenant_id`, `source_system`, `interface_name`, `extract_date`, `payload_format`, `s3_object_key`, `payload_json`, `checksum`, `status`, `error_message`, `created_at`.  
  전표와의 연결은 **fi_doc_header.raw_event_id → sap_raw_events.id** 로만 이뤄집니다.

- **agent_activity_log**에는 **`resource_type` + `resource_id`** 만 있습니다.  
  - Aura push / Audit ingest 시 `resource_type` = `"CASE"` 또는 `"AGENT_CASE"`, `resource_id` = caseId(문자열).  
  - 전표(doc)를 직접 가리키는 컬럼은 없고, **agent_case(bukrs, belnr, gjahr)** 로 전표와 연결됩니다.

따라서 **“resource_key”** 는 본 설계에서 **전표 기준 식별자 = docKey (`bukrs-belnr-gjahr`) 로 통일**합니다.

- **fi_doc_header**: (bukrs, belnr, gjahr) → docKey = `bukrs-belnr-gjahr`
- **Lineage API 경로**: `GET /api/v1/synapse/lineage/{resourceKey}` 에서 `resourceKey` = docKey
- **조회 로직**: docKey 파싱 → (bukrs, belnr, gjahr) → fi_doc_header 확인 → 해당 전표에 연결된 **agent_case** 조회 → 각 case에 대한 **agent_activity_log** (resource_type=AGENT_CASE, resource_id=caseId) + **agent_action** 수집 → 시간순 정렬 후 그래프 DTO 구성

즉, **동일 규격**은 “전표 = docKey(bukrs-belnr-gjahr)” 로 정의하고, raw event / activity log는 이 docKey와 **fi_doc_header ↔ agent_case** 를 통해 간접 연결됩니다.

---

### 2. 테넌트 격리(`tenant_id`)가 모든 계보 조회 로직에 적용되어 있습니까?

**답변:**

- **기존** `LineageQueryService.findLineage(tenantId, query)` 는 이미 모든 repository 호출에 `tenantId` 조건을 사용합니다.
- **Phase 3** 에서 추가하는 **resourceKey(docKey) 기반 그래프 조회** 에서도:
  - fi_doc_header: `tenant_id` + (bukrs, belnr, gjahr)
  - agent_case: `tenant_id` + (bukrs, belnr, gjahr)
  - agent_activity_log: `tenant_id` + resource_type + resource_id IN (caseIds)
  - agent_action: `tenant_id` + case_id IN (caseIds)
  - sap_raw_events: 조회 시 `tenant_id` 필터
  **모든 조회에 `tenant_id` 가 필수로 적용됩니다.**

---

## DTO 구조: LineageGraphDto (Source → Agent → Case → Action)

프론트엔드가 그래프를 그리기 쉽도록 **노드 + 엣지** 형태로 정규화합니다.

### 노드 타입

| 타입     | 설명                     | 식별자 예시        |
|----------|--------------------------|--------------------|
| SOURCE   | 전표 원천 (raw event/doc)| docKey, rawEventId |
| AGENT    | 에이전트 활동 1건        | activityId         |
| CASE     | 케이스 1건               | caseId             |
| ACTION   | 실행 액션 1건            | actionId           |

### DTO 정의

- **LineageGraphDto**
  - `resourceKey`: String (docKey)
  - `nodes`: `List<LineageNodeDto>` (SOURCE, AGENT, CASE, ACTION 혼합, 시간순 정렬 권장)
  - `edges`: `List<LineageEdgeDto>` (fromId → toId)

- **LineageNodeDto**
  - `id`: String (노드 고유 ID, 예: "source-{docKey}", "agent-{activityId}", "case-{caseId}", "action-{actionId}")
  - `type`: Enum "SOURCE" | "AGENT" | "CASE" | "ACTION"
  - `label`: String (표시용)
  - `refId`: String (원본 ID: rawEventId, activityId, caseId, actionId)
  - `occurredAt`: Instant (nullable, 정렬/연결용)
  - `payload`: Map 또는 전용 필드 (type별 상세)

- **LineageEdgeDto**
  - `fromId`: String (노드 id)
  - `toId`: String (노드 id)

**계층 연결 규칙**

- SOURCE → AGENT: 동일 docKey에서 파생된 활동
- AGENT → CASE: agent_activity_log.resource_id = caseId
- CASE → ACTION: agent_action.case_id = caseId

(실제 구현 시 SOURCE를 하나 두고, 해당 doc에 연결된 모든 AGENT 노드를 SOURCE에 연결; AGENT는 resource_id로 CASE에 연결; CASE는 action의 case_id로 ACTION에 연결.)

---

## Service 수정안

### LineageQueryService

- **메서드 추가**: `findLineageGraphByResourceKey(Long tenantId, String resourceKey)`
  1. **DocKey 검증**: `DocKeyUtil.parse(resourceKey)` → bukrs, belnr, gjahr. 실패 시 INVALID_INPUT_VALUE.
  2. **fi_doc_header 조회**: `fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(tenantId, bukrs, belnr, gjahr)`. 없으면 빈 그래프 또는 404 정책에 따라 반환.
  3. **agent_case 목록**: `agentCaseRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(tenantId, bukrs, belnr, gjahr)`.
  4. **agent_activity_log**: caseId 목록에 대해 `tenant_id + resource_type=AGENT_CASE + resource_id IN (caseIds)` 로 한 번에 조회, **occurred_at ASC** 정렬 (시간순).  
     - Repository에 `findByTenantIdAndResourceTypeAndResourceIdInOrderByOccurredAtAsc(tenantId, "AGENT_CASE", caseIdList, Pageable)` 또는 유사 메서드 추가.
  5. **agent_action**: `agentActionRepository.findByTenantIdAndCaseIdIn(tenantId, caseIds)` (해당 메서드 없으면 findByCaseId 반복 또는 Custom 구현).
  6. **그래프 조립**: SOURCE 노드 1개 → AGENT 노드들(시간순) → CASE 노드들 → ACTION 노드들, 엣지 SOURCE→AGENT, AGENT→CASE, CASE→ACTION 생성.
  7. **반환**: `LineageGraphDto`.

### 인덱스 활용

- **V19**: `ix_agent_activity_log_tenant_resource (tenant_id, resource_type, resource_id)`  
  → `(tenant_id, resource_type, resource_id IN (...))` 조건에 활용 가능.  
  **resource_id IN** 에 대해서는 인덱스가 부분 활용되며, case 수가 많지 않으면 충분. 필요 시 QueryDSL로 `resource_id.in(caseIds)` 한 번에 조회해 N+1 제거.

### Repository 추가

- **AgentActivityLogRepository**:  
  `List<AgentActivityLog> findByTenantIdAndResourceTypeAndResourceIdInOrderByOccurredAtAsc(Long tenantId, String resourceType, Collection<String> resourceIds);`  
  (Spring Data JPA는 `Collection` IN 쿼리 지원. 정렬만 주의.)
- **AgentActionRepository**:  
  caseId 목록 기준 조회 메서드 유무 확인 후, 없으면 `findByTenantIdAndCaseIdIn` 또는 QueryDSL로 일괄 조회.

---

## Controller

- **기존**: `GET /synapse/lineage` (query: caseId, docKey, rawEventId, partyId)
- **추가**: `GET /synapse/lineage/{resourceKey}`  
  - `resourceKey` = docKey (bukrs-belnr-gjahr)  
  - 헤더: `X-Tenant-ID` 필수  
  - 응답: `ApiResponse<LineageGraphDto>`  
  - docKey 형식 오류 시 400, (선택) 전표 없을 때 404

---

## 요약

| 항목           | 내용 |
|----------------|------|
| resource_key   | sap_raw_events에는 해당 컬럼 없음. **docKey(bukrs-belnr-gjahr)** 로 통일하고, fi_doc_header·agent_case·agent_activity_log를 이 키로 연결. |
| tenant 격리    | 모든 계보 조회(기존 + resourceKey 그래프)에 `tenant_id` 필수 적용. |
| DTO            | LineageGraphDto (nodes, edges), LineageNodeDto (id, type, label, refId, occurredAt, payload), LineageEdgeDto (fromId, toId). |
| 성능           | agent_activity_log는 (tenant_id, resource_type, resource_id IN caseIds) + occurred_at ASC 로 1회 조회; AgentAction은 caseId 목록 1회 조회. |
