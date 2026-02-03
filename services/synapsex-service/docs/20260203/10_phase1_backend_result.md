# Phase 1 Backend 작업결과 — Data & Evidence Hub

**기준 문서**: `10_phase1_backend_prompt.md`  
**작업일**: 2026-02-03

---

## 1. FI Documents API

### 1.1 목록 `GET /api/synapse/documents`
- **추가/변경 쿼리 파라미터**: `fromBudat`, `toBudat`, `partyId`, `integrityStatus`, `hasCase`
- **응답 DTO**: `DocumentListRowDto` — docKey, bukrs, belnr, gjahr, budat, bldat, blart, tcode, usnam, kunnr, lifnr, counterpartyName, wrbtr, waers, xblnr, bktxt, integrityStatus(PASS|WARN|FAIL), reversalFlag, reversesDocKey, reversedByDocKey, linkedCasesCount
- **Tenant Scope**: bukrs 필터는 `TenantScopeResolver`로 허용 회사코드만 적용
- **integrityStatus 필터**: FAIL(item 없음), WARN(ingestion error 있음), PASS(정상) — DB 서브쿼리로 구현

### 1.2 상세 `GET /api/synapse/documents/{docKey}`
- **docKey 형식**: `bukrs-belnr-gjahr`
- **레거시 경로**: `GET /api/synapse/documents/{bukrs}/{belnr}/{gjahr}` 유지

### 1.3 Reversal Chain `GET /api/synapse/documents/{docKey}/reversal-chain`
- **응답**: `DocumentReversalChainDto` — nodes, edges (BFS로 전체 체인 수집)
- **노드**: docKey, belnr, reversalBelnr, budat

### 1.4 유틸
- `DocKeyUtil`: docKey 파싱/포맷 (bukrs-belnr-gjahr)

---

## 2. Open Items API

### 2.1 목록 `GET /api/synapse/open-items`
- **추가 쿼리 파라미터**: `type`(AR|AP), `fromDueDate`, `toDueDate`, `daysPastDueMin`, `daysPastDueMax`, `status`(OPEN|PARTIALLY_CLEARED|CLEARED), `q`(lifnr/kunnr/xblnr 검색)
- **응답 DTO**: `OpenItemListRowDto` — openItemKey, type, amount, status, daysPastDue, partyName, docKey, blockReason, recommendedAction, guardrailStatus(기본 ALLOWED)
- **Tenant Scope**: bukrs 필터 적용

### 2.2 상세 `GET /api/synapse/open-items/{openItemKey}`
- **openItemKey 형식**: `bukrs-belnr-gjahr-buzei`
- **clearingHistory**: 빈 리스트 (open_item_clearing_history 테이블 없음)
- **레거시 경로**: `GET /api/synapse/open-items/{bukrs}/{belnr}/{gjahr}/{buzei}` 유지

### 2.3 유틸
- `OpenItemKeyUtil`: openItemKey 파싱/포맷

---

## 3. Entity Hub API

### 3.1 목록 `GET /api/synapse/entities`
- **추가 쿼리 파라미터**: `bukrs`
- **응답 DTO**: `EntityListRowDto` — partyId, type, name, country, riskScore(0-100), riskTrend(STABLE), openItemsCount, openItemsTotal, overdueCount, overdueTotal, recentAnomaliesCount, lastChangedAt
- **Tenant Scope**: bukrs 필터 — fi_doc_item 기준 partyCode(lifnr/kunnr)로 scope 내 회사코드만 노출

### 3.2 Entity 360 `GET /api/synapse/entities/{partyId}`
- 기존 구현 유지 (partyId 숫자만 매칭)

### 3.3 Change Logs `GET /api/synapse/entities/{partyId}/change-logs`
- **응답**: `PageResponse<EntityChangeLogDto>` — changenr, udate, utime, tabname, fname, valueOld, valueNew
- **SoT**: `sap_change_log` (objectid = party.partyCode)

### 3.4 Related Objects
- `GET /api/synapse/entities/{partyId}/documents` → `PageResponse<DocumentListRowDto>`
- `GET /api/synapse/entities/{partyId}/open-items` → `PageResponse<OpenItemListRowDto>`
- `GET /api/synapse/entities/{partyId}/cases` → `PageResponse<CaseListRowDto>`

---

## 4. Lineage API

### 4.1 목록/검색 `GET /api/synapse/lineage`
- **기존 구현 유지**: caseId, docKey, rawEventId, partyId (최소 1개), asOf
- **응답**: journeyNodes, timestamps, evidencePanel, asOfSnapshot, currentSnapshot, timeTravelDegraded

### 4.2 Time-Travel `GET /api/synapse/lineage/time-travel`
- **추가 엔드포인트**: partyId, asOf 쿼리 파라미터
- **Phase1 MVP**: 기존 findLineage 로직 재사용, change log 기반 추정

---

## 5. 공통

- **tenant_id**: 모든 API 필터 강제
- **bukrs**: Tenant Scope 허용 회사코드만
- **ApiResponse<T>**: 모든 응답 래핑
- **page, size, sort**: 표준 페이징/정렬

---

## 6. 미구현/제한사항

| 항목 | 비고 |
|------|------|
| fi_doc_integrity_check | DDL에 없음 → integrityStatus는 derive (item count, ingestion error 기반) |
| open_item_clearing_history | DDL에 없음 → clearingHistory 빈 리스트 |
| PII 마스킹 | policy_pii_field + config_profile — Phase1 최소, 추후 적용 |
| guardrailStatus | Open Items — 기본 ALLOWED, GuardrailEvaluate 연동 추후 |
| riskTrend | Entity — 기본 STABLE, 추후 계산 로직 |
| SapChangeLogRepository | countByTenantIdAndObjectid 추가 |

---

## 7. 변경 파일 목록

- `DocKeyUtil.java` (신규)
- `OpenItemKeyUtil.java` (신규)
- `DocumentController.java`
- `DocumentQueryService.java`
- `DocumentReversalChainDto.java`
- `OpenItemController.java`
- `OpenItemQueryService.java`
- `OpenItemListRowDto.java`
- `OpenItemDetailDto.java`
- `EntityController.java`
- `EntityQueryService.java`
- `EntityListRowDto.java`
- `EntityChangeLogDto.java` (신규)
- `SapChangeLogRepository.java`
- `LineageController.java`
