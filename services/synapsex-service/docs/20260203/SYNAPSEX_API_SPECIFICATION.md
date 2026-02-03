# SynapseX API 명세서

> **버전**: 1.0  
> **작성일**: 2026-02-03  
> **대상**: 프론트엔드 팀 전달용  
> **Base URL**: `https://{gateway-host}/api/synapse` (Gateway 8080 경유)

---

## 공통 사항

### 필수 헤더
| 헤더 | 타입 | 필수 | 설명 |
|------|------|------|------|
| X-Tenant-ID | Long | ✅ | 테넌트 식별자 |
| X-User-ID | Long | - | 사용자 식별자 (변경 이력 시) |
| Authorization | Bearer {JWT} | ✅ | 인증 토큰 |

### 응답 래퍼 (ApiResponse<T>)
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { ... },
  "success": true,
  "timestamp": "2026-02-03T12:00:00"
}
```

### 페이징 (PageResponse<T>)
```json
{
  "items": [ ... ],
  "total": 42,
  "pageInfo": {
    "page": 0,
    "size": 20,
    "hasNext": true
  }
}
```

### 공통 쿼리 파라미터 (목록 API)
| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| page | int | 0 | 0-based 페이지 |
| size | int | 20 | 페이지 크기 (최대 100) |
| sort | string | - | `field` 또는 `field,asc` / `field,desc` |

### 에러 코드
| HTTP | errorCode | 용도 |
|------|-----------|------|
| 400 | VALIDATION_ERROR, INVALID_INPUT_VALUE | 파라미터 오류, 헤더 누락 |
| 404 | ENTITY_NOT_FOUND | 리소스 미존재 |
| 409 | DUPLICATE_ENTITY | 중복 생성 |

---

## Phase 1: Data & Evidence Hub

### 1.1 FI Documents (전표)

| Method | Path | 설명 |
|--------|------|------|
| GET | /documents | 전표 목록 |
| GET | /documents/{docKey} | 전표 상세 (docKey: bukrs-belnr-gjahr) |
| GET | /documents/{bukrs}/{belnr}/{gjahr} | 전표 상세 (레거시) |
| GET | /documents/{docKey}/reversal-chain | Reversal 체인 |

#### GET /documents (목록)
**Query**
| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| fromBudat | date (YYYY-MM-DD) | - | posting date from |
| toBudat | date | - | posting date to |
| bukrs | string | - | 회사코드 |
| belnr | string | - | 전표번호 |
| gjahr | string | - | 회계연도 |
| partyId | long | - | 거래처 ID (kunnr/lifnr) |
| integrityStatus | string | - | PASS \| WARN \| FAIL |
| hasCase | boolean | - | 케이스 연관 여부 |
| q | string | - | belnr, xblnr, bktxt, usnam, tcode 검색 |
| usnam, tcode, xblnr, statusCode, lifnr, kunnr | string | - | 필터 |
| hasReversal | boolean | - | 역분개 여부 |
| amountMin, amountMax | decimal | - | 금액 범위 |
| page, size, sort | - | - | 페이징 |

**Response**: `PageResponse<DocumentListRowDto>`
- docKey, bukrs, belnr, gjahr, budat, bldat, blart, tcode, usnam
- kunnr, lifnr, counterpartyName, wrbtr, waers, xblnr, bktxt
- integrityStatus, reversalFlag, reversesDocKey, reversedByDocKey, linkedCasesCount

#### GET /documents/{docKey}
**Path**: docKey = `bukrs-belnr-gjahr`

**Response**: DocumentDetailDto (header, items, integrityChecks, reversalChain, linkedObjects)

#### GET /documents/{docKey}/reversal-chain
**Response**: `{ nodes: [...], edges: [...] }`

---

### 1.2 Open Items (미결항목)

| Method | Path | 설명 |
|--------|------|------|
| GET | /open-items | 오픈아이템 목록 |
| GET | /open-items/{openItemKey} | 상세 (openItemKey: bukrs-belnr-gjahr-buzei) |
| GET | /open-items/{bukrs}/{belnr}/{gjahr}/{buzei} | 상세 (레거시) |

#### GET /open-items (목록)
**Query**
| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| type | string | - | AR \| AP |
| bukrs | string | - | 회사코드 |
| partyId | long | - | 거래처 ID |
| fromDueDate, toDueDate | date | - | 만기일 범위 |
| daysPastDueMin, daysPastDueMax | int | - | 연체일수 |
| status | string | - | OPEN \| PARTIALLY_CLEARED \| CLEARED |
| paymentBlock, disputeFlag | boolean | - | |
| q | string | - | lifnr, kunnr, xblnr 검색 |
| page, size, sort | - | - | 페이징 |

**Response**: `PageResponse<OpenItemListRowDto>`
- openItemKey, type, amount, currency, dueDate, daysPastDue, status
- partyId, partyName, docKey, disputeFlag, paymentBlock, guardrailStatus

---

### 1.3 Entity Hub (거래처)

| Method | Path | 설명 |
|--------|------|------|
| GET | /entities | 거래처 목록 |
| GET | /entities/{partyId} | Entity 360 상세 |
| GET | /entities/{partyId}/change-logs | 변경 이력 |
| GET | /entities/{partyId}/documents | 연관 전표 |
| GET | /entities/{partyId}/open-items | 연관 오픈아이템 |
| GET | /entities/{partyId}/cases | 연관 케이스 |

#### GET /entities (목록)
**Query**
| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| type | string | - | VENDOR \| CUSTOMER |
| bukrs | string | - | 회사코드 |
| country | string | - | |
| riskMin, riskMax | double | - | 리스크 점수 |
| hasOpenItems | boolean | - | |
| q | string | - | name, code 검색 |
| page, size, sort | - | - | 페이징 |

**Response**: `PageResponse<EntityListRowDto>`
- partyId, type, name, country, riskScore, riskTrend
- openItemsCount, openItemsTotal, overdueCount, overdueTotal, recentAnomaliesCount

#### GET /entities/{partyId}/change-logs
**Query**: page, size

**Response**: `PageResponse<EntityChangeLogDto>`

---

### 1.4 Lineage (추적/증거)

| Method | Path | 설명 |
|--------|------|------|
| GET | /lineage | Journey 조회 (caseId, docKey, rawEventId, partyId 중 1개 필수) |
| GET | /lineage/time-travel | Time-travel (partyId, asOf) |

#### GET /lineage
**Query**
| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| caseId | long | - | 케이스 ID |
| docKey | string | - | 전표 키 |
| rawEventId | long | - | Raw 이벤트 ID |
| partyId | long | - | 거래처 ID |
| asOf | datetime | - | 시점 기준 |

**Response**: LineageResponseDto
- journeyNodes, timestamps, evidencePanel, asOfSnapshot, currentSnapshot

---

### 1.5 Entity Scope (레거시/Scope 샘플)

| Method | Path | 설명 |
|--------|------|------|
| GET | /entities/fi-doc-headers | Scope 적용 doc 헤더 샘플 |
| GET | /entities/fi-open-items | Scope 적용 오픈아이템 샘플 |
| GET | /entities/cases | Scope 적용 케이스 샘플 |
| GET | /entities/actions | Scope 적용 액션 샘플 |

**Query**: limit (default 100)

---

## Phase 2: Autonomous Operations

### 2.1 Cases (케이스)

| Method | Path | 설명 |
|--------|------|------|
| GET | /cases | 케이스 목록 |
| GET | /cases/{caseId} | 케이스 상세 |
| POST | /cases/{caseId}/status | 상태 변경 |

#### GET /cases (목록)
**Query**
| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| status | string | - | |
| severity | string | - | |
| caseType | string | - | |
| detectedFrom, detectedTo | datetime | - | |
| bukrs, belnr, gjahr, buzei | string | - | |
| partyId | long | - | |
| page, size, sort | - | - | 페이징 |

**Response**: `PageResponse<CaseListRowDto>`

#### POST /cases/{caseId}/status
**Body**: `{ "status": "string" }`

---

### 2.2 Anomalies (이상)

| Method | Path | 설명 |
|--------|------|------|
| GET | /anomalies | 이상 목록 |

**Query**
| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| severity | string | - | |
| anomalyType | string | - | |
| detectedFrom, detectedTo | datetime | - | |
| page, size, sort | - | - | 페이징 |

**Response**: `PageResponse<AnomalyListRowDto>`

---

### 2.3 Actions (액션)

| Method | Path | 설명 |
|--------|------|------|
| GET | /actions | 액션 목록 |
| POST | /actions | 액션 생성 |
| POST | /actions/{actionId}/approve | 승인 |
| POST | /actions/{actionId}/execute | 실행 |

#### GET /actions (목록)
**Query**
| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| status | string | - | |
| type | string | - | |
| caseId | long | - | |
| createdFrom, createdTo | datetime | - | |
| page, size, sort | - | - | 페이징 |

**Response**: `PageResponse<ActionListRowDto>`

#### POST /actions
**Body**: `{ "caseId": long, "actionType": string, "payload": object }`

---

### 2.4 Archive (아카이브)

| Method | Path | 설명 |
|--------|------|------|
| GET | /archive | 실행된/실패한 액션 목록 |

**Query**
| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| outcome | string | - | |
| type | string | - | |
| from, to | datetime | - | |
| page, size, sort | - | - | 페이징 |

**Response**: `PageResponse<ArchiveListRowDto>`

---

## Phase 3: Knowledge & Policy Hub

### 3.1 RAG (문서 라이브러리)

| Method | Path | 설명 |
|--------|------|------|
| GET | /rag/documents | RAG 문서 목록 |
| POST | /rag/documents | RAG 문서 등록 |
| GET | /rag/documents/{docId} | RAG 문서 상세 |
| GET | /rag/search | 검색 |

#### GET /rag/documents

**Query**: status, page, size

**Response**: `PageResponse<RagDocumentListDto>`

#### POST /rag/documents

**Body**: RegisterRagDocumentRequest (JSON)

#### GET /rag/search

**Query**: q (필수), page, size, sort

**Response**: `PageResponse<RagSearchResultDto>`

---

### 3.2 Policies (정책)

| Method | Path | 설명 |
|--------|------|------|
| GET | /policies/profiles | 프로파일 목록 |
| GET | /policies/profiles/{profileId} | 프로파일 상세 |
| GET | /policies/effective | 유효 정책 조회 |

#### GET /policies/effective

**Query**: profileId, bukrs

**Response**: EffectivePolicyDto

---

### 3.3 Guardrails (가드레일)

| Method | Path | 설명 |
|--------|------|------|
| GET | /guardrails | 가드레일 목록 |
| POST | /guardrails | 가드레일 생성 |
| PUT | /guardrails/{guardrailId} | 가드레일 수정 |
| DELETE | /guardrails/{guardrailId} | 가드레일 삭제 |
| POST | /guardrails/evaluate | 가드레일 평가 |

**Query (목록)**: enabledOnly, page, size, sort

---

### 3.4 Dictionary (사전)

| Method | Path | 설명 |
|--------|------|------|
| GET | /dictionary | 용어 목록 |
| POST | /dictionary | 용어 생성 |
| PUT | /dictionary/{termId} | 용어 수정 |
| DELETE | /dictionary/{termId} | 용어 삭제 |

**Query (목록)**: category, page, size, sort

---

### 3.5 Feedback (피드백)

| Method | Path | 설명 |
|--------|------|------|
| GET | /feedback | 피드백 목록 |
| POST | /feedback | 피드백 등록 |

**Query (목록)**: targetType, targetId, page, size, sort

**Body (POST)**: FeedbackCreateRequest (targetType, targetId, label, comment 등)

---

## Phase 4: Reconciliation & Audit

### 4.1 Reconciliation (정합성)

| Method | Path | 설명 |
|--------|------|------|
| POST | /reconciliation/runs | 정합성 실행 시작 |
| GET | /reconciliation/runs | 실행 목록 |
| GET | /reconciliation/runs/{runId} | 실행 상세 |

#### POST /reconciliation/runs

**Body**: StartReconRequest (runType 등)

**Response**: ReconRunDetailDto

---

### 4.2 Action Reconciliation

| Method | Path | 설명 |
|--------|------|------|
| GET | /action-recon | 액션 정합성 요약 |

**Response**: ActionReconDto (successRate, totalExecuted, successCount, failedCount, failureReasons, impactSummary)

---

### 4.3 Audit (감사)

| Method | Path | 설명 |
|--------|------|------|
| GET | /audit/events | 감사 이벤트 검색 |
| GET | /audit/events/{auditId} | 감사 이벤트 상세 |
| POST | /audit/export | 감사 Export 요청 |

#### GET /audit/events

**Query**
| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| from, to | datetime | - | |
| category, type, outcome, severity | string | - | |
| actorUserId, actorType | - | - | |
| resourceType, resourceId | string | - | |
| q | string | - | |
| page, size, sort | - | - | (Pageable) |

**Response**: AuditEventPageDto

---

### 4.4 Analytics

| Method | Path | 설명 |
|--------|------|------|
| GET | /analytics/kpis | KPI 조회 |

**Query**: from, to (date), dims

**Response**: AnalyticsKpiDto (savingsEstimate, preventedLossEstimate, medianTimeToTriageHours, automationRate, additionalMetrics)

---

## Admin API

> Base: `/api/synapse/admin`

### Admin Profiles

| Method | Path | 설명 |
|--------|------|------|
| GET | /admin/profiles | 프로파일 목록 |
| POST | /admin/profiles | 프로파일 생성 |
| PUT | /admin/profiles/{profileId} | 프로파일 수정 |
| PUT | /admin/profiles/{profileId}/default | 기본 프로파일 설정 |
| DELETE | /admin/profiles/{profileId} | 프로파일 삭제 |

---

### Admin Thresholds

| Method | Path | 설명 |
|--------|------|------|
| GET | /admin/thresholds | 임계값 검색 |
| POST | /admin/thresholds | 임계값 upsert |
| DELETE | /admin/thresholds/{thresholdId} | 임계값 삭제 |

**Query (GET)**: profileId, dimension, waers, q, page, size, sort

---

### Admin PII Policy

| Method | Path | 설명 |
|--------|------|------|
| GET | /admin/pii-policies | PII 정책 목록 (profileId 필수) |
| PUT | /admin/pii-policies/bulk | PII 정책 일괄 저장 |
| GET | /admin/pii-fields/catalog | PII 필드 카탈로그 |

---

### Admin Data Protection

| Method | Path | 설명 |
|--------|------|------|
| GET | /admin/data-protection | 데이터 보호 정책 (profileId 필수) |
| PUT | /admin/data-protection | 데이터 보호 정책 수정 |

---

### Admin Tenant Scope

| Method | Path | 설명 |
|--------|------|------|
| GET | /admin/tenant-scope | 스코프 요약 |
| GET | /admin/tenant-scope/company-codes | 회사코드 목록 |
| PUT | /admin/tenant-scope/company-codes/bulk | 회사코드 일괄 |
| GET | /admin/tenant-scope/currencies | 통화 목록 |
| PUT | /admin/tenant-scope/currencies/bulk | 통화 일괄 |
| GET | /admin/tenant-scope/sod-rules | SoD 규칙 목록 |
| PUT | /admin/tenant-scope/sod-rules/bulk | SoD 규칙 일괄 |
| PATCH | /admin/tenant-scope/company-codes/{bukrs} | 회사코드 단건 |
| PATCH | /admin/tenant-scope/currencies/{waers} | 통화 단건 |
| PATCH | /admin/tenant-scope/sod-rules/{ruleKey} | SoD 규칙 단건 |

---

### Admin SoD

| Method | Path | 설명 |
|--------|------|------|
| POST | /admin/sod/evaluate | SoD 평가 |

---

### Admin Catalog

| Method | Path | 설명 |
|--------|------|------|
| GET | /admin/catalog/company-codes | 회사코드 카탈로그 |
| GET | /admin/catalog/currencies | 통화 카탈로그 |
| GET | /admin/catalog | 전체 카탈로그 |

---

### Admin Governance Config

| Method | Path | 설명 |
|--------|------|------|
| GET | /admin/governance-config | 거버넌스 설정 |
| PATCH | /admin/governance-config/{configKey} | 설정 수정 |

---

## 키 형식 정의

| 키 | 형식 | 예시 |
|----|------|------|
| docKey | bukrs-belnr-gjahr | 1000-4900000001-2024 |
| openItemKey | bukrs-belnr-gjahr-buzei | 1000-4900000001-2024-001 |
| partyId | Long | 12345 |
| caseId | Long | 1 |
| actionId | Long | 1 |
| auditId | Long | 1 |

---

## OpenAPI

- **Swagger UI**: `http://{host}:8085/swagger-ui.html` (그룹: synapse)
- **OpenAPI JSON**: `http://{host}:8085/v3/api-docs/synapse`
- **Gateway 경유**: `http://{gateway}:8080/api/synapse/...` 으로 호출 시 위 경로로 라우팅됨
