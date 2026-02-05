# DB 테이블/컬럼 코멘트 현행화

## 개요

fi_doc_header, fi_doc_item, fi_open_item, bp_party, agent_case, agent_action 등 누락된 `COMMENT ON`을 Flyway 마이그레이션으로 추가함.

## 적용 방법

### 1) 재기동 시 자동 적용 (권장)

서비스 재기동 시 Flyway가 마이그레이션을 자동 적용함.

- **synapsex-service**: `V25__add_table_column_comments.sql` → dwp_aura 스키마
- **dwp-main-service**: `V4__add_agent_tasks_column_comments.sql` → public 스키마

### 2) 직접 적용 (Flyway 미사용 시)

```bash
# synapse (dwp_aura) DB
psql -h localhost -U dwp_user -d dwp_aura -f services/synapsex-service/src/main/resources/db/migration/V25__add_table_column_comments.sql

# main DB (agent_tasks)
psql -h localhost -U dwp_user -d dwp_main -f dwp-main-service/src/main/resources/db/migration/V4__add_agent_tasks_column_comments.sql
```

## 적용 대상

| DB | 마이그레이션 | 대상 테이블 |
|----|-------------|-------------|
| synapse (dwp_aura) | V25 | sap_raw_events, ingestion_errors, fi_doc_header, fi_doc_item, fi_open_item, bp_party, bp_party_pii_vault, sap_change_log, agent_case, agent_action, integration_outbox, policy_doc_metadata, detect_run, ingest_run, audit_event_log, tenant_company_code_scope, tenant_currency_scope, tenant_sod_rule, tenant_scope_seed_state, policy_data_protection, md_company_code, md_currency, policy_scope_company, policy_scope_currency, policy_sod_rule, rag_document, rag_chunk, policy_guardrail, dictionary_term, feedback_label, recon_run, recon_result, analytics_kpi_daily |
| main | V4 | agent_tasks (id, description, input_data, result_data, error_message, started_at, completed_at, created_at, updated_at) |

## auth DB

auth_db는 DDL 추출 시 이미 com_tenants, com_users, com_roles 등 대부분 테이블에 코멘트가 있음. 별도 마이그레이션 없음.
