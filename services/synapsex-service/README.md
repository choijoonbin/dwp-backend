# SynapseX Service

## 1. 프로젝트 개요

**SynapseX**는 DWP(Digital Work Platform) 백엔드의 **Self-Healing Finance(자동화 재무 거버넌스)** 도메인 서비스입니다.

- **역할**: Aura-Platform(Python/FastAPI) AI 에이전트가 호출하는 **재무 정책·거버넌스·감사** API 제공
- **통합**: DWP Gateway(8080) 경유 `/api/synapse/**` → synapsex-service(8085)
- **스키마**: PostgreSQL `dwp_aura` 스키마 전용
- **기술 스택**: Java 17, Spring Boot 3.4.x, JPA, Flyway, dwp-core

### 1.1 핵심 기능

| 영역 | 설명 |
|------|------|
| **Admin Console** | Config Profile, Tenant Scope(회사코드/통화/SoD), PII 정책, Data Protection, 거버넌스 설정 |
| **Scope Enforcement** | 전표/미결제/케이스/조치 조회 시 tenant_id + bukrs/currency 스코프 필터 강제 |
| **감사 로그** | Admin 정책 변경·스코프 위반 등 `audit_event_log` 기록 |
| **FI Entity 조회** | 전표(fi_doc_header), 미결제(fi_open_item), 케이스(agent_case), 조치(agent_action) 스코프 필터 적용 |

---

## 2. 아키텍처 및 구성

### 2.1 패키지 구조

```
com.dwp.services.synapsex/
├── audit/              # 감사 이벤트 상수
├── controller/         # REST 컨트롤러
│   ├── Admin*         # Admin Console API
│   ├── AuditEventController
│   └── FiDocumentScopeController
├── dto/admin/         # Admin DTO
├── entity/            # JPA 엔티티
├── repository/        # JPA Repository
├── scope/             # ScopeResolver, TenantScopeResolver
├── service/
│   ├── admin/        # Admin 비즈니스 로직 (Query/Command/Validator 분리)
│   │   ├── *QueryService   # 조회 전용
│   │   ├── *CommandService # 생성/수정/삭제
│   │   └── *Validator      # 입력 검증
│   ├── audit/        # AuditWriter, AuditEventQueryService
│   └── scope/        # ScopeEnforcementService
└── SynapsexServiceApplication
```

**유지보수 규칙 준수**: Controller 250라인, Service 350라인 제한. TenantScope는 Query/Command/Validator 분리.

### 2.2 Gateway 라우팅

| Path | 대상 서비스 | 용도 |
|------|-------------|------|
| `/api/synapse/admin/**` | synapsex-service:8085 | Admin Console API |
| `/api/synapse/audit/**` | synapsex-service:8085 | 감사 로그 조회 |
| `/api/synapse/entities/**` | synapsex-service:8085 | FI 전표/미결제/케이스/조치 조회 |

### 2.3 DB 마이그레이션 (Flyway)

| 버전 | 파일 | 내용 |
|------|------|------|
| V1 | `create_schema_dwp_self_healing` | 초기 스키마 |
| V2 | `rename_schema_to_aura_service` | dwp_aura 스키마 |
| V3 | `self_healing_finance_tables` | fi_doc_header, fi_open_item, agent_case, agent_action, sap_raw_events 등 |
| V4 | `config_schema_tables` | config_profile, config_kv, rule_*, policy_* |
| V5 | `synapse_audit_event_log` | 감사 로그 테이블 |
| V6 | `audit_event_log` | audit_event_log 통합 |
| V7 | `app_codes_and_config_kv_governance` | AppCode, 거버넌스 |
| V8 | `tenant_scope_tables` | tenant_company_code_scope, tenant_currency_scope, tenant_sod_rule |
| V9 | `policy_data_protection_and_pii_extensions` | policy_data_protection, policy_pii_field 확장 |
| V10 | `profile_scoped_tenant_scope_and_masters` | md_company_code, md_currency, policy_scope_*, policy_sod_rule |

---

## 3. 현재 진행 현황

### 3.1 완료된 기능 ✅

| 기능 | 상태 | 비고 |
|------|------|------|
| Config Profile CRUD | ✅ | 기본 프로파일 1개/테넌트 강제 |
| Tenant Scope (tenant-level) | ✅ | V8 테이블, PATCH/POST bulk |
| Tenant Scope (profile-scoped) | ✅ | V10, GET/PUT company-codes, currencies, sod-rules |
| PII Policy (policy_pii_field) | ✅ | GET/PUT bulk, mask_rule/hash_rule/encrypt_rule |
| Data Protection | ✅ | 암호화, 보존기간, kmsMode, 내보내기 제어 |
| 거버넌스 Config (config_kv) | ✅ | key-value 설정 |
| 한도 정책 (rule_threshold) | ✅ | dimension/dimension_key 기반 |
| SoD 평가 (skeleton) | ✅ | POST /sod/evaluate |
| Catalog (회사코드/통화) | ✅ | FI 데이터 기반 카탈로그 |
| 감사 로그 | ✅ | audit_event_log, Admin 변경·스코프 DENIED 기록 |
| Scope Enforcement (읽기) | ✅ | fi-doc-headers, fi-open-items, cases, actions |
| Scope Enforcement (쓰기) | ✅ | ScopeEnforcementService, 403 OUT_OF_SCOPE |
| Swagger/OpenAPI | ✅ | springdoc-openapi-starter-webmvc-ui |

### 3.2 미구현/진행 예정 ⏳

| 기능 | 상태 | 비고 |
|------|------|------|
| 전표/미결제/케이스/조치 **상세** API | ⏳ | 목록만 구현, 상세(detail) 엔드포인트 미구현 |
| 조치 실행/승인/시뮬레이션 API | ⏳ | 상태 변경 시 스코프 검사 필요 |
| SAP 데이터 적재 파이프라인 | ⏳ | sap_raw_events → fi_* 적재 로직 |
| Agent Case/Action 생성·업데이트 | ⏳ | Aura-Platform 연동 시 |
| 중복송장 룰(rule_duplicate_invoice) API | ⏳ | 테이블만 존재 |
| policy_action_guardrail API | ⏳ | 테이블만 존재 |
| policy_notification_channel API | ⏳ | 테이블만 존재 |
| SoD 실제 평가 로직 | ⏳ | 현재 스켈레톤만 |
| config_kv security.sod_mode 연동 | ⏳ | SoD mode(PLANNED/BASELINE/ENFORCED) |
| X-Profile-ID 헤더 전파 | ⏳ | 요청별 profileId 전달 시 스코프 정확 적용 |

### 3.3 진행률 요약

| 영역 | 완료 | 전체 | 비고 |
|------|------|------|------|
| Admin Console (탭) | 3/3 | 3 | Tenant Scope, PII & Encryption, Data Protection |
| Scope Enforcement | 읽기 완료 | 읽기+쓰기 | 쓰기는 헬퍼만, 실제 액션 API 미연결 |
| FI Entity API | 목록 4종 | 목록+상세+액션 | 상세·액션 미구현 |
| 감사/정책 | 완료 | 완료 | - |
| SAP 적재/에이전트 연동 | 0% | - | 별도 Phase |

---

## 4. API 요약

### 4.1 Admin API (`/api/synapse/admin/`)

| 컨트롤러 | 경로 예시 | 설명 |
|----------|-----------|------|
| AdminProfileController | `/profiles`, `/profiles/{id}` | 프로파일 CRUD |
| SynapseTenantScopeAdminController | `/tenant-scope`, `/tenant-scope/company-codes`, `/currencies`, `/sod-rules` | Tenant Scope (tenant + profile-scoped) |
| AdminPiiPolicyController | `/pii-policies`, `/pii-fields/catalog` | PII 정책 |
| AdminDataProtectionController | `/data-protection` | 데이터 보호(암호화/보존) |
| AdminGovernanceConfigController | `/governance-config` | config_kv 거버넌스 |
| AdminCatalogController | `/catalog/company-codes`, `/catalog/currencies` | 카탈로그 |
| AdminSodController | `/sod/evaluate` | SoD 평가 |
| AdminThresholdController | `/thresholds` | 한도 정책 |

### 4.2 Audit API (`/api/synapse/audit/`)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/events` | 감사 이벤트 목록 (페이징, 필터) |
| GET | `/events/{id}` | 감사 이벤트 상세 |

### 4.3 Entity API (`/api/synapse/entities/`)

| Method | Path | 스코프 조건 |
|--------|------|-------------|
| GET | `/fi-doc-headers` | tenant_id + bukrs IN scope |
| GET | `/fi-open-items` | tenant_id + bukrs IN scope + currency IN scope |
| GET | `/cases` | tenant_id + bukrs IN scope |
| GET | `/actions` | case 조인, case bukrs IN scope |

---

## 5. Scope 규칙

- **Tenant Scope**는 **선택된 Policy Profile(profileId)** 기준 적용
- **profileId 없음** → 테넌트 기본 프로파일(`is_default=true`) 사용
- **policy_scope_company/currency 행 없음** → `md_company_code`/`md_currency` 전체 포함 (included-by-default)
- **스코프에서 제외된** Company Code(BUKRS)/Currency → 목록 미노출, 직접 URL 접근 시 **403 OUT_OF_SCOPE**

---

## 6. 실행 방법

```bash
# DB: PostgreSQL (dwp_aura 스키마, Flyway 자동 마이그레이션)
# 환경변수: DB_HOST, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD

./gradlew :services:synapsex-service:bootRun
# 기본 포트: 8085
```

Gateway(8080) 경유 시: `http://localhost:8080/api/synapse/admin/profiles` 등

---

## 7. 관련 문서

| 문서 | 경로 |
|------|------|
| Tenant Scope & Catalog API | `docs/frontend-src/docs/api-spec/synapse-spec/TENANT_SCOPE_AND_CATALOG_API_FE_HANDOVER.md` |
| Profile-Scoped Tenant Scope 결과 | `docs/frontend-src/docs/api-spec/synapse-spec/PROFILE_SCOPED_TENANT_SCOPE_AND_DATA_PROTECTION_result.md` |
| PII & Encryption Admin Tab | `docs/frontend-src/docs/api-spec/synapse-spec/SYNAPSE_PII_ENCRYPTION_ADMIN_TAB3_result.md` |
| Admin Audit API | `docs/frontend-src/docs/api-spec/synapse-spec/SYNAPSE_ADMIN_AUDIT_API_result.md` |

---

## 8. 의존성

- **dwp-core**: ApiResponse, ErrorCode, BaseException, HeaderConstants
- **Spring Boot**: web, data-jpa, validation
- **PostgreSQL**, **Flyway**
- **springdoc-openapi**: Swagger UI
