# 설계부족 — Phase 1 Data & Trust API

> **작성일**: 2026-02-02  
> **대상**: Phase 1 — /documents, /open-items, /entities, /lineage  
> **목적**: 화면 동작에 필요한 API 누락 사항 정리

---

## 1. 개요

Phase 1 Data & Trust 화면 구현 시 아래 API가 필요합니다.  
현재 백엔드 `FiDocumentScopeController`(`/api/synapse/entities/`)에는 **목록 API만** 존재하며, **상세·필터·엔티티·계보** API가 누락되어 있습니다.

---

## 2. Documents (전표)

### 2.1 현재 상태

| API | 경로 | 상태 |
|-----|------|------|
| 목록 | `GET /api/synapse/entities/fi-doc-headers` | ✅ 존재 (limit만 지원) |
| 상세 | - | ❌ 미구현 |
| 라인 아이템 | - | ❌ 미구현 |
| 리버설 체인 | - | ❌ 미구현 |
| 무결성 체크 | - | ❌ 미구현 |

### 2.2 필요 API

#### A. 전표 목록 (필터 확장)

**현재**: `GET /api/synapse/entities/fi-doc-headers?limit=100`

**요청**: 아래 쿼리 파라미터 지원

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `dateFrom` | ISO date | posting date 범위 시작 |
| `dateTo` | ISO date | posting date 범위 종료 |
| `bukrs` | string | 회사코드 (복수 가능) |
| `status` | string | statusCode |
| `hasReversal` | boolean | reversal_belnr 존재 여부 |
| `usnam` | string | 생성자 |
| `tcode` | string | 트랜잭션 코드 |
| `xblnr` | string | 외부 참조 번호 (부분 일치) |
| `amountMin` | number | 금액 범위 최소 |
| `amountMax` | number | 금액 범위 최대 |
| `page` | number | 페이지 (0-based) |
| `size` | number | 페이지 크기 (기본 20) |

**응답**: 페이징 메타 포함 (`total`, `page`, `size`, `totalPages`)

#### B. 전표 상세

**요청**: `GET /api/synapse/documents/{bukrs}/{belnr}/{gjahr}`  
또는 `GET /api/synapse/entities/fi-doc-headers/{bukrs}/{belnr}/{gjahr}`

**응답**: FiDocHeader 전체 필드 + `counterparty`, `counterpartyId`, `wrbtr`(header total), `integrityStatus`, `reversalFlag`, `reversedByDoc`, `reversesDoc`, `linkedCasesCount`

#### C. 전표 라인 아이템

**요청**: `GET /api/synapse/documents/{bukrs}/{belnr}/{gjahr}/lines`  
또는 `GET /api/synapse/entities/fi-doc-items?bukrs=&belnr=&gjahr=`

**응답**: FiDocItem[] (buzei, hkont, hkontName, shkzg, wrbtr, mwskz, kostl, prctr, zuonr, sgtxt, lifnr, kunnr)

#### D. 리버설 체인

**요청**: 상세 API 응답에 `reversalChain: FiDocHeader[]` 포함  
또는 별도 `GET /api/synapse/documents/{bukrs}/{belnr}/{gjahr}/reversal-chain`

#### E. 무결성 체크

**요청**: `GET /api/synapse/documents/{bukrs}/{belnr}/{gjahr}/integrity-checks`

**응답**: `{ ruleName, severity, passed, evidence, recommendation, relatedCaseId? }[]`

---

## 3. Open Items (미결제)

### 3.1 현재 상태

| API | 경로 | 상태 |
|-----|------|------|
| 목록 | `GET /api/synapse/entities/fi-open-items` | ✅ 존재 (limit만 지원) |
| 상세 | - | ❌ 미구현 |

### 3.2 필요 API

#### A. 미결제 목록 (필터 확장)

**현재**: `GET /api/synapse/entities/fi-open-items?limit=100`

**요청**: 아래 쿼리 파라미터 지원

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `dueFrom` | ISO date | 만기일 범위 |
| `dueTo` | ISO date | 만기일 범위 |
| `cleared` | boolean | cleared 여부 |
| `paymentBlock` | boolean | payment_block |
| `disputeFlag` | boolean | dispute_flag |
| `itemType` | string | AP \| AR |
| `bukrs` | string | 회사코드 |
| `partyId` | string | lifnr 또는 kunnr (거래처) |
| `docKey` | string | bukrs-belnr-gjahr (전표 기준 필터) |
| `page` | number | 페이지 |
| `size` | number | 페이지 크기 |

**응답**: 페이징 메타 + `entityName`, `entityId` (lifnr/kunnr), `docNumber`(belnr), `daysPastDue`, `blockReason`, `recommendedAction`, `guardrailStatus` 등 UI 표시용 필드

---

## 4. Entities (거래처)

### 4.1 현재 상태

| API | 경로 | 상태 |
|-----|------|------|
| 목록 | - | ❌ 미구현 |
| 상세 | - | ❌ 미구현 |

**참고**: `BpParty` 엔티티 존재. Controller 미구현.

### 4.2 필요 API

#### A. 거래처 목록

**요청**: `GET /api/synapse/entities/parties?type=vendor|customer&riskLevel=high|medium|low&highExposure=true&page=&size=`

> **참고**: 기존 `/api/synapse/entities`는 fi-doc-headers 등과 경로가 겹치므로 `/entities/parties` 사용 권장

**응답**: `{ partyId, partyCode, name, type, country, riskScore, openItemsTotal, openItemsCount, overdueTotal, overdueCount, lastChange, ... }[]`

#### B. 거래처 상세 (Entity 360)

**요청**: `GET /api/synapse/entities/parties/{partyId}`

**응답**:

- KPI: exposure, overdue, avgDays, riskTrend
- 기본 정보: code, name, type, country, bankAccount(masked), taxId(masked), address, contactName, contactEmail, paymentTerms
- PII 필드: 마스킹 적용. `Request Access` CTA용 플래그 `piiAccessRequestable`

#### C. 거래처 변경 이력 (타임라인)

**요청**: `GET /api/synapse/entities/{partyId}/change-log?from=&to=&limit=`

**응답**: `{ timestamp, fieldName, beforeValue, afterValue, actor, actorType, source, severity }[]`

#### D. 거래처 탭 데이터

**요청**:

- `GET /api/synapse/entities/{partyId}/documents` — 전표 목록
- `GET /api/synapse/entities/{partyId}/open-items` — 미결제 목록
- `GET /api/synapse/entities/{partyId}/cases` — 케이스 목록

---

## 5. Lineage (계보·근거)

### 5.1 현재 상태

| API | 경로 | 상태 |
|-----|------|------|
| 계보 조회 | - | ❌ 미구현 |

### 5.2 필요 API

#### A. 계보 Journey 조회

**요청**: `GET /api/synapse/lineage?caseId=&docKey=&rawEventId=&partyId=&asOf=`

**쿼리**: 위 파라미터 중 하나 이상 필수. `asOf`는 Time Travel용 ISO datetime.

**응답**:

- `steps: { id, label, type, status, timestamp, ... }[]` — Journey 단계
- `evidence`: ingestion errors, case evidence, rag refs, statistical evidence
- `timeTravelSnapshots`: asOf vs current 비교용 스냅샷 (필드별 before/after)

---

## 6. 공통 요청 사항

1. **X-Tenant-ID**: 필수 (현재 세션/테넌트 컨텍스트)
2. **X-User-ID**: 선택 (감사용)
3. **X-Profile-ID**: (선택) Tenant Scope 적용 시 profileId 전달
4. **응답 형식**: `ApiResponse<T>` (status, data, message) 준수
5. **Scope Enforcement**: tenant_id + bukrs/currency scope 적용 유지

---

## 7. 우선순위 제안

| 순위 | API | 화면 영향 |
|------|-----|-----------|
| 1 | Documents 목록 필터 확장 | /documents 필터바 |
| 2 | Documents 상세 + 라인 | /documents/{bukrs}/{belnr}/{gjahr} |
| 3 | Open Items 목록 필터 확장 | /open-items 필터바 |
| 4 | Entities 목록 | /entities |
| 5 | Entities 상세 + 탭 | /entities/{partyId} |
| 6 | Lineage Journey | /lineage |
| 7 | 리버설 체인, 무결성 체크, 변경 이력 | 상세 화면 보강 |

---

*문서 끝*
