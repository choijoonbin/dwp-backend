# SynapseX 서비스 소스 구조 점검

> **작성일**: 2026-02-03  
> **목적**: 메뉴별 폴더 구조화 및 소스 구조 점검

---

## 1. 메뉴별 Controller ↔ Service 매핑

| 메뉴 | Controller | Service | 비고 |
|------|------------|---------|------|
| **Phase1: Documents** | DocumentController | document/DocumentQueryService | ✅ |
| **Phase1: Open Items** | OpenItemController | openitem/OpenItemQueryService | ✅ |
| **Phase1: Entities** | EntityController, FiDocumentScopeController | entity/EntityQueryService | entities 하위 fi-doc-headers 등 |
| **Phase1: Lineage** | LineageController | lineage/LineageQueryService | ✅ |
| **Phase2: Cases** | CaseController | case_/CaseQueryService, CaseCommandService | ✅ |
| **Phase2: Anomalies** | AnomalyController | anomaly/AnomalyQueryService | ✅ |
| **Phase2: Actions** | ActionController | action/ActionQueryService, ActionCommandService | ✅ |
| **Phase2: Archive** | ArchiveController | archive/ArchiveQueryService | ✅ |
| **Phase3: RAG** | RagController | rag/RagQueryService, RagCommandService | ✅ |
| **Phase3: Policies** | PolicyController | policy/PolicyQueryService | ✅ |
| **Phase3: Guardrails** | GuardrailController | guardrail/* | ✅ |
| **Phase3: Dictionary** | DictionaryController | dictionary/* | ✅ |
| **Phase3: Feedback** | FeedbackController | feedback/* | ✅ |
| **Phase4: Reconciliation** | ReconciliationController | recon/ReconRunService | ✅ |
| **Phase4: Action Recon** | ActionReconController | actionrecon/ActionReconQueryService | ✅ |
| **Phase4: Audit** | AuditEventController, AuditExportController | audit/* | ✅ |
| **Phase4: Analytics** | AnalyticsController | analytics/AnalyticsKpiQueryService | ✅ |
| **Admin** | Admin*Controller, SynapseTenantScopeAdminController | admin/* | profiles, thresholds, pii, sod, catalog 등 |

---

## 2. 폴더 구조 평가

### 2.1 장점
- **Controller**: 메뉴별 1:1 매핑 (DocumentController, CaseController 등)
- **Service**: 도메인별 패키지 분리 (document, case_, action, entity 등)
- **DTO**: 도메인별 패키지 (dto/document, dto/case_ 등)
- **Repository**: 엔티티별 1:1

### 2.2 개선 권장
| 항목 | 현재 | 권장 |
|------|------|------|
| FiDocumentScopeController | EntityController와 동일 /entities 경로 | Entity scope 전용 하위 경로로 명확화 또는 EntityController로 통합 |
| Admin 컨트롤러 | 8개 분산 (profiles, thresholds, pii, sod, catalog, governance, data-protection, tenant-scope) | admin/ 하위 패키지로 Controller 그룹화 |
| case_ 패키지명 | case_ (예약어 회피) | case 패키지 또는 cases |

### 2.3 경로 충돌
- **EntityController** `/{partyId:[0-9]+}` vs **FiDocumentScopeController** `/fi-doc-headers`, `/cases`, `/actions`
  - partyId는 숫자만 매칭하여 경로 충돌 없음
  - FiDocumentScopeController의 cases, actions는 EntityController의 /{partyId}/cases 등과 다른 용도 (scope 샘플 조회)

---

## 3. 소스 구조 요약

```
synapsex-service/
├── controller/          # 27개 (메뉴별 API 진입점)
├── service/             # 도메인별 Query/Command 분리
│   ├── document/        # DocumentQueryService
│   ├── openitem/        # OpenItemQueryService
│   ├── entity/          # EntityQueryService
│   ├── lineage/         # LineageQueryService
│   ├── case_/           # CaseQueryService, CaseCommandService
│   ├── action/          # ActionQueryService, ActionCommandService
│   ├── anomaly/         # AnomalyQueryService
│   ├── archive/        # ArchiveQueryService
│   ├── rag/             # RagQueryService, RagCommandService
│   ├── policy/          # PolicyQueryService
│   ├── guardrail/      # GuardrailQueryService, GuardrailCommandService, GuardrailEvaluateService
│   ├── dictionary/      # DictionaryQueryService, DictionaryCommandService
│   ├── feedback/        # FeedbackQueryService, FeedbackCommandService
│   ├── recon/           # ReconRunService
│   ├── actionrecon/     # ActionReconQueryService
│   ├── audit/           # AuditEventQueryService, AuditExportService, AuditWriter
│   ├── analytics/       # AnalyticsKpiQueryService
│   ├── admin/           # 18개 (ConfigProfile, Threshold, PiiPolicy, TenantScope, Sod 등)
│   └── scope/           # ScopeEnforcementService
├── dto/                 # 67개 (도메인별)
├── entity/              # JPA 엔티티
├── repository/          # 36개
├── scope/               # TenantScopeResolver, ScopeResolver
├── audit/               # AuditEventConstants
└── util/                # DocKeyUtil, OpenItemKeyUtil
```

---

## 4. 결론

- **메뉴별 구조화**: ✅ 양호 (Controller-Service-DTO 도메인 일치)
- **계층 분리**: ✅ Controller(매핑) / Service(로직) / Repository(DB) 명확
- **Admin 영역**: 다수 컨트롤러 분산이나 admin 패키지로 서비스 통합됨
