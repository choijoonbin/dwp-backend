# API Contract Validation Report — Synapse API

> **작성일**: 2026-01-29  
> **대상**: `/api/synapse/**` 전체 엔드포인트  
> **목적**: UI 요구사항 및 API 계약 검증

---

## 1. 검증 요약

| 항목 | 상태 | 비고 |
|------|------|------|
| List 엔드포인트 page/size/sort | ✅ | 모든 목록 API 지원 |
| PageResponse (items + total + pageInfo) | ✅ | 통일 적용 |
| Deep-link keys (documents, open-items, entities, cases/actions) | ✅ | DTO에 포함 |
| Tenant isolation (X-Tenant-ID) | ✅ | 모든 쿼리 필터링 |
| Error codes (400/404/409) | ✅ | GlobalExceptionHandler |
| OpenAPI specs (/api/synapse/**) | ✅ | springdoc 그룹 "synapse" |

---

## 2. List 엔드포인트 PageResponse 지원

### 2.1 page / size / sort 파라미터

모든 목록 API는 다음 파라미터를 지원합니다.

| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| `page` | int | 0 | 0-based 페이지 번호 |
| `size` | int | 20 | 페이지 크기 (최대 100) |
| `sort` | string | (엔드포인트별 기본) | 정렬: `field` 또는 `field,asc` / `field,desc` |

### 2.2 PageResponse 형식

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

### 2.3 적용된 엔드포인트

| 엔드포인트 | page | size | sort | PageResponse |
|------------|------|------|------|--------------|
| GET /synapse/documents | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/open-items | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/entities | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/cases | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/actions | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/anomalies | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/archive | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/rag/documents | ✅ | ✅ | - | ✅ |
| GET /synapse/rag/search | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/dictionary | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/guardrails | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/feedback | ✅ | ✅ | ✅ | ✅ |
| GET /synapse/reconciliation/runs | ✅ | ✅ | ✅ | ✅ |

---

## 3. Deep-link Keys

각 행(row)에 UI 딥링크에 필요한 키가 포함됩니다.

| 리소스 | Deep-link Keys | DTO 필드 |
|--------|----------------|----------|
| **documents** | bukrs, belnr, gjahr | `DocumentListRowDto`: bukrs, belnr, gjahr |
| **open-items** | bukrs, belnr, gjahr, buzei | `OpenItemListRowDto`: bukrs, belnr, gjahr, buzei |
| **entities** | partyId | `EntityListRowDto`: partyId |
| **cases** | caseId | `CaseListRowDto`: caseId |
| **actions** | caseId, actionId | `ActionListRowDto`: actionId, caseId |
| **anomalies** | caseId(=anomalyId), docKeys, partyIds | `AnomalyListRowDto`: anomalyId, docKeys, partyIds |
| **archive** | actionId, caseId, docKey, partyId | `ArchiveListRowDto`: actionId, caseId, docKey, partyId |

---

## 4. Tenant Isolation

- **필수 헤더**: `X-Tenant-ID` (Long)
- **적용**: 모든 컨트롤러에서 `@RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId` 사용
- **DB 필터**: 모든 조회 시 `tenant_id = tenantId` 조건 적용
- **헤더 누락 시**: `MissingRequestHeaderException` → 400 (VALIDATION_ERROR)

---

## 5. Error Codes

| HTTP | ErrorCode | 용도 |
|------|-----------|------|
| 400 | VALIDATION_ERROR | @Valid 실패, 필수 헤더 누락, 파라미터 타입 불일치 |
| 404 | ENTITY_NOT_FOUND | 리소스 미존재 |
| 409 | DUPLICATE_ENTITY | 중복 생성 시도 |

**GlobalExceptionHandler** (`dwp-core`):
- `BaseException` → ErrorCode 기반 HTTP 상태
- `MethodArgumentNotValidException` → 400
- `BindException` → 400
- `MissingRequestHeaderException` → 400
- `MethodArgumentTypeMismatchException` → 400 (INVALID_INPUT_VALUE)
- `IllegalArgumentException` → 400 (INVALID_INPUT_VALUE)

---

## 6. OpenAPI Specs

- **경로**: `/v3/api-docs/synapse` (synapse 그룹)
- **Swagger UI**: `/swagger-ui.html` (그룹 선택: synapse)
- **설정**: `OpenApiConfig` (`synapsex-service/config`)
- **포함 경로**: `/synapse/**` 전체

---

## 7. 변경 사항 요약 (이번 작업)

1. **Dictionary, Guardrails, Feedback, Reconciliation list** → PageResponse + page/size/sort
2. **Actions, Anomalies, Archive** → sort 파라미터 추가
3. **MissingRequestHeaderException** → 400 핸들러 추가
4. **OpenApiConfig** → synapse 그룹 및 기본 설정
5. **Repository** → Dictionary, Feedback, Guardrail, ReconRun에 Pageable 메서드 추가

---

*문서 끝*
