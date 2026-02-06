# Audit Logging Phase1 P0 — event_type 매핑표

## 엔드포인트 → event_type / resource_type / resource_id

| Endpoint | event_type | event_category | resource_type | resource_id |
|----------|------------|----------------|---------------|-------------|
| **Cases** | | | | |
| GET /api/synapse/cases | CASE_VIEW_LIST | CASE | CASE | null |
| GET /api/synapse/cases/{id} | CASE_VIEW_DETAIL | CASE | CASE | caseId |
| POST /api/synapse/cases/{id}/status | STATUS_CHANGE | CASE | AGENT_CASE | caseId |
| POST /api/synapse/cases/{id}/assign | CASE_ASSIGN | CASE | AGENT_CASE | caseId |
| POST /api/synapse/cases/{id}/comment | CASE_COMMENT_CREATE | CASE | CASE_COMMENT | commentId |
| **Documents** | | | | |
| GET /api/synapse/documents | DOCUMENT_VIEW_LIST | ACTION | DOCUMENT | null |
| GET /api/synapse/documents/{docKey} | DOCUMENT_VIEW_DETAIL | ACTION | DOCUMENT | docKey |
| GET /api/synapse/documents/{bukrs}/{belnr}/{gjahr} | DOCUMENT_VIEW_DETAIL | ACTION | DOCUMENT | docKey |
| GET /api/synapse/documents/{docKey}/reversal-chain | DOCUMENT_VIEW_DETAIL | ACTION | DOCUMENT | docKey |
| **Open Items** | | | | |
| GET /api/synapse/open-items | OPENITEM_VIEW_LIST | ACTION | OPEN_ITEM | null |
| GET /api/synapse/open-items/{openItemKey} | OPENITEM_VIEW_DETAIL | ACTION | OPEN_ITEM | openItemKey |
| GET /api/synapse/open-items/{bukrs}/{belnr}/{gjahr}/{buzei} | OPENITEM_VIEW_DETAIL | ACTION | OPEN_ITEM | openItemKey |
| **Actions** | | | | |
| GET /api/synapse/actions | ACTION_VIEW_LIST | ACTION | ACTION | null |
| GET /api/synapse/actions/{id} | ACTION_VIEW_DETAIL | ACTION | ACTION | actionId |
| POST /api/synapse/actions | (ACTION_PROPOSED - createAction 내부) | ACTION | AGENT_ACTION | actionId |
| POST /api/synapse/actions/{id}/simulate | SIMULATE | ACTION | AGENT_ACTION | actionId |
| POST /api/synapse/actions/{id}/approve | APPROVE | ACTION | AGENT_ACTION | actionId |
| POST /api/synapse/actions/{id}/reject | REJECT | ACTION | AGENT_ACTION | actionId |
| POST /api/synapse/actions/{id}/execute | EXECUTE | ACTION | AGENT_ACTION | actionId |
| POST /api/synapse/actions/{id}/resume | EXECUTE | ACTION | AGENT_ACTION | actionId |
| **Audit** | | | | |
| GET /api/synapse/audit/events | AUDIT_VIEW_LIST | AUDIT | AUDIT_EVENT | null |
| GET /api/synapse/audit/events/{auditId} | AUDIT_VIEW_DETAIL | AUDIT | AUDIT_EVENT | auditId |
| **Batch** | | | | |
| POST /api/synapse/admin/detect/run | RUN_DETECT_MANUAL_TRIGGERED | RUN | DETECT_RUN | runId |

## tags 규칙

- **LIST**: page, size, sort, order + 필터 요약 (PII 금지)
- **DETAIL**: resource 식별자 (caseId, docKey, openItemKey, actionId, auditId)
- **WRITE**: before_json/after_json/diff_json (가능한 경우)

## outcome / severity

- 성공: outcome=SUCCESS, severity=INFO
- 실패: outcome=FAILED, severity=WARN 또는 ERROR, evidence_json에 errorCode/exception 요약
