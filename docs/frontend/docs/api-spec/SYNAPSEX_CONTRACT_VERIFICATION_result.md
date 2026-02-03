# SynapseX Contract & Verification 결과

> **작성일**: 2026-01-29  
> **목표**: Phase2~4 동시 완료 시 "붙였을 때 터지는" 일 방지, OpenAPI 계약 고정, Contract Test, Audit 의무 검증, Phase 시드

---

## 1. OpenAPI 계약 고정

| 항목 | 구현 |
|------|------|
| `/openapi.json` | `OpenApiController` 추가 — `/v3/api-docs/synapse`로 forward |
| 버전 고정 | `OpenApiConfig` info.version = "1.0" |
| 프론트 TS 생성 | `GET /openapi.json` → 스펙 다운로드 → openapi-generator 등으로 클라이언트 생성 |
| Breaking change 감지 | PR에서 openapi.json diff 확인 권장 |

**경로**: `services/synapsex-service/src/main/java/com/dwp/services/synapsex/controller/OpenApiController.java`

---

## 2. Contract Tests (Backend)

| 엔드포인트 | 검증 항목 |
|------------|-----------|
| GET /synapse/cases | status 200, ApiResponse, items/total/pageInfo, X-Tenant-ID 없으면 400 |
| GET /synapse/cases/{id} | 상세 조회 |
| GET /synapse/anomalies | PageResponse 스키마, X-Tenant-ID 400 |
| GET /synapse/optimization/ar | ApiResponse, buckets, X-Tenant-ID 400 |
| GET /synapse/actions | PageResponse 스키마, X-Tenant-ID 400 |
| GET /synapse/archive | PageResponse 스키마, X-Tenant-ID 400 |

**경로**: `services/synapsex-service/src/test/java/com/dwp/services/synapsex/controller/SynapseContractTest.java`

---

## 3. Audit 의무 이벤트 통합테스트

| 이벤트 | 트리거 API | 검증 |
|--------|------------|------|
| CASE_ASSIGN | POST /synapse/cases/{id}/assign | audit_event_log.event_type = CASE_ASSIGN |
| CASE_STATUS_CHANGE | POST /synapse/cases/{id}/status | event_type = STATUS_CHANGE |
| CASE_COMMENT_CREATE | POST /synapse/cases/{id}/comment | event_type = CASE_COMMENT_CREATE |
| ACTION_SIMULATE | POST /synapse/actions/{id}/simulate | event_type = SIMULATE |
| ACTION_APPROVE | POST /synapse/actions/{id}/approve | event_type = APPROVE |
| ACTION_EXECUTE | POST /synapse/actions/{id}/execute | event_type = EXECUTE |

**경로**: `services/synapsex-service/src/test/java/com/dwp/services/synapsex/integration/AuditMandatoryEventIntegrationTest.java`  
**베이스**: `SynapseTestcontainersBase` (PostgreSQL Testcontainers)

---

## 4. Phase 시드 스크립트

| 리소스 | tenant=1 시드 |
|--------|---------------|
| documents | fi_doc_header, fi_doc_item (1000/1900000001/2024) |
| open-items | fi_open_item (AP 1건) |
| entities | bp_party (VENDOR V001, CUSTOMER C001) |
| cases | agent_case (DUPLICATE_INVOICE 1건) |
| actions | agent_action (PAYMENT_BLOCK PROPOSED 1건) |
| audit | audit_event_log (CASE_VIEW_LIST 1건) |

**실행**:
```bash
./scripts/run_synapse_seed.sh
# 또는
psql -h localhost -U dwp_user -d dwp_aura -f scripts/seed_synapse_phase.sql
```

**경로**: `scripts/seed_synapse_phase.sql`, `scripts/run_synapse_seed.sh`

---

## 5. 수정 사항

- **ActionController**: `auditWriter` 필드 누락 → 추가
- **build.gradle**: Testcontainers 의존성 추가 (synapsex-service)

---

## 6. E2E Smoke (Playwright) / Front Contract Validation

- **E2E Smoke**: 프론트엔드 레포에서 Playwright로 tenant=1 기준 시나리오 10개 자동화 (별도 작업)
- **Front Zod validate**: 개발모드 런타임 schema 검증, ApiResponse unwrap 실패 시 Sentry/console 표준화 (FE 작업)

---

*문서 끝*
