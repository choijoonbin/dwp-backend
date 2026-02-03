# Phase2~4 구현 로드맵

> 작성일: 2026-02-03  
> 목표: Phase2~4 실제 동작 완성, audit_event_log 의무 기록

---

## 완료 (Phase2 Cases)

### 1) Case Worklist 확장
- **GET /api/synapse/cases**  
  - 추가 쿼리: assigneeUserId, companyCode, waers, dateFrom, dateTo, q, savedViewKey  
  - SoT: agent_case (assignee_user_id, saved_view_key 추가)  
  - Audit: CASE_VIEW_LIST (category=ACTION)

### 2) Case Detail + Timeline + Assign + Comment
- **GET /api/synapse/cases/{caseId}** — Audit: CASE_VIEW_DETAIL  
- **GET /api/synapse/cases/{caseId}/timeline** — 상태변경/코멘트 이력  
- **POST /api/synapse/cases/{caseId}/assign** — assigneeUserId  
- **POST /api/synapse/cases/{caseId}/status** — NEW/IN_PROGRESS/WAITING_APPROVAL/RESOLVED/DISMISSED  
- **POST /api/synapse/cases/{caseId}/comment** — 코멘트 등록  
- SoT: case_comment, agent_case  
- Audit: CASE_ASSIGN, CASE_STATUS_CHANGE, CASE_COMMENT_CREATE

### DDL (V16)
- agent_case: assignee_user_id, saved_view_key  
- case_comment  
- policy_suggestion  
- feedback_label: correct_action, case_id

### AuditEventConstants 확장
- TYPE_VIEW_LIST, TYPE_VIEW_DETAIL, TYPE_ASSIGN, TYPE_COMMENT_CREATE  
- TYPE_ANOMALY_VIEW_*, TYPE_OPTIMIZATION_VIEW  
- TYPE_REQUEST_INFO, TYPE_RAG_DOC_*, TYPE_POLICY_CHANGE, TYPE_GUARDRAIL_CHANGE  
- TYPE_DICTIONARY_CHANGE, TYPE_FEEDBACK_LABEL_CREATE, TYPE_POLICY_SUGGESTION_CREATE

---

## 미완료 (추가 구현 필요)

### Phase2
- [ ] Anomalies: rule_duplicate_invoice/rule_threshold 기반 뷰, ANOMALY_VIEW_LIST/DETAIL audit  
- [ ] Optimization: GET /optimization/ar, /optimization/ap (open_item 버킷/연체예측)  
- [ ] Actions: POST /actions/{id}/simulate, request-info, integration_outbox 연동  
- [ ] Archive: GET /archive/actions, /archive/actions/{id}

### Phase3
- [ ] RAG: multipart upload, 인덱싱 상태, RAG_DOC_* audit  
- [ ] Policies: rule_duplicate_invoice, rule_threshold GET/PUT  
- [ ] Guardrails: policy_action_guardrail GET/PUT  
- [ ] Dictionary: CRUD + DICTIONARY_CHANGE audit  
- [ ] Feedback: POST /feedback/labels, /feedback/policy-suggestions

### Phase4
- [ ] Reconciliation: GET /reconciliation/ingestion, /reconciliation/integrity  
- [ ] Action-recon: agent_action vs outbox 상태  
- [ ] Analytics: GET /analytics/impact

---

## SoT 매핑 (DDL 기준)

| 도메인 | SoT 테이블 |
|--------|------------|
| Case | agent_case, case_comment |
| Action | agent_action, agent_action_simulation, integration_outbox |
| Document | fi_doc_header, fi_doc_item |
| Open Item | fi_open_item |
| Entity | bp_party |
| RAG | rag_document, rag_chunk |
| Policy | rule_duplicate_invoice, rule_threshold, policy_action_guardrail |
| Feedback | feedback_label, policy_suggestion |
| Recon | recon_run, recon_result |
| Audit | audit_event_log |
