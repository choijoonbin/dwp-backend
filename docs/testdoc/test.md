2026-02-25 20:29:29,307 - api.middleware - INFO - [0f936cc6-8e04-456e-ac1c-ed2422b59014] POST /aura/detect/screen-batch - Client: 127.0.0.1
2026-02-25 20:29:29,382 - api.routes.aura_detect - INFO - Aura detect_screen_batch called: n=1 case_ids=[''] (BE가 이 로그가 보이면 실제로 Aura를 호출한 것)
2026-02-25 20:29:29,383 - core.analysis.precheck_pipeline - INFO - Screening batch input: n=1 case_ids=[''] (판단 기준: 금액+시각+업종, 6종 caseType)
2026-02-25 20:29:29,383 - core.analysis.precheck_pipeline - INFO - Screening batch context summary: vouchers_with_occurredAt=1 vouchers_with_hrStatus=1 vouchers_with_mcc=1 vouchers_with_budgetExceeded=1 (of n=1)
2026-02-25 20:29:29,396 - core.llm.prompts - INFO - Loaded YAML prompt: screening.yaml (version: 3.2.0)
2026-02-25 20:29:33,060 - core.analysis.precheck_pipeline - INFO - Screening result: caseId=(n/a) caseType=HOLIDAY_USAGE severity=MEDIUM score=75 reasonText=발생 시각이 주말(토요일)이며, 근태 상태가 LEAVE로 확인되어 휴일 사용 위험으로 분류됨.
2026-02-25 20:29:33,060 - core.analysis.precheck_pipeline - INFO - Screening reasonText corrected(caseType=HOLIDAY_USAGE): before=발생 시각이 주말(토요일)이며, 근태 상태가 LEAVE로 확인되어 휴일 사용 위험으로 분류됨. after=2026-03-21는 토요일이며, 근태 상태(LEAVE)와 결합해 휴일 사용 위험으로 분류함.
2026-02-25 20:29:33,061 - api.routes.aura_detect - INFO - Aura detect_screen_batch response: n=1 caseTypes=['HOLIDAY_USAGE'] (Aura는 caseType DEFAULT를 반환하지 않음)
INFO:     127.0.0.1:58616 - "POST /aura/detect/screen-batch HTTP/1.1" 200 OK
2026-02-25 20:29:33,062 - api.middleware - INFO - [0f936cc6-8e04-456e-ac1c-ed2422b59014] POST /aura/detect/screen-batch - Status: 200 - Duration: 3.756s
2026-02-25 20:29:33,792 - api.middleware - INFO - [6e6c37e0-9f78-443f-91cc-3213da9e9bd6] POST /aura/cases/136/analysis-runs - Client: 127.0.0.1
INFO:     127.0.0.1:58616 - "POST /aura/cases/136/analysis-runs HTTP/1.1" 202 Accepted
2026-02-25 20:29:33,868 - api.middleware - INFO - [6e6c37e0-9f78-443f-91cc-3213da9e9bd6] POST /aura/cases/136/analysis-runs - Status: 202 - Duration: 0.076s
2026-02-25 20:29:33,896 - core.analysis.agent_factory - INFO - discover_available_agents: Found 5 active agents for tenant 1 (raw=5)
2026-02-25 20:29:34,337 - api.routes.aura_cases - INFO - case_analysis_stream: start consuming run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136
INFO:     127.0.0.1:58686 - "GET /aura/cases/136/analysis/stream?runId=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 HTTP/1.1" 200 OK
2026-02-25 20:29:34,338 - api.routes.aura_cases - INFO - case_analysis_stream: first chunk sent run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2
2026-02-25 20:29:34,664 - core.analysis.agent_factory - INFO - select_agent_for_request: selected key=finance_aura (llm)
2026-02-25 20:29:34,744 - core.analysis.agent_factory - INFO - [AgentConfig] docIds 파싱 완료: 2개 문서 할당됨 (doc_ids=[23, 26])
2026-02-25 20:29:34,744 - core.stores.config_store - INFO - [AgentConfigStore] Cached config for 1:finance_aura (ttl=300.0s, docIds=2개)
2026-02-25 20:29:34,745 - core.analysis.analysis_pipeline - INFO - audit_analysis start case_id=136 run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 body_evidence_keys=['evidence', 'ragRefs', 'doc_id', 'voucher_key', 'item_id', 'voucher_item_no', 'case_type', 'screening_reason_text', 'occurredAt', 'expenseType', 'merchantName', 'amount', 'hrStatus', 'mccCode', 'budgetExceeded', 'intended_risk_type', 'hrStatusRaw', 'isHoliday', 'mccName', 'risk_category', 'is_weekend_allowed', 'document', 'voucher', 'partyIds', 'openItems', 'lineage', 'policies']
2026-02-25 20:29:34,745 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'INPUT_NORM', 'percent': 10}
2026-02-25 20:29:34,746 - core.analysis.analysis_pipeline - INFO - audit_analysis payload-first case_id=136 fields=['amount', 'belnr', 'budgetExceeded', 'bukrs', 'buzei', 'case_type', 'doc_id', 'expenseType', 'gjahr', 'hrStatus', 'hrStatusRaw', 'isHoliday', 'item_id', 'mccCode', 'mccCodeRaw', 'mccName', 'merchantName', 'occurredAt', 'screening_reason_text'] case_type=HOLIDAY_USAGE occurredAt=2026-03-21T00:06:04+09:00 amount=48244.0 mccCode=5814 hrStatus=LEAVE hrStatusRaw=LEAVE isHoliday=True
2026-02-25 20:29:34,746 - core.analysis.analysis_pipeline - INFO - audit_analysis skip get_case fallback case_id=136 reason=payload_sufficient has_amount=True has_occurredAt=True has_case_type=True has_doc_keys=True
2026-02-25 20:29:34,746 - core.analysis.analysis_pipeline - INFO - audit_analysis normalized_input: case_id=136 has_caseType=True caseType=HOLIDAY_USAGE has_reasonText=True mccCode=5814 mccName=패스트푸드 reasonText_preview=2026-03-21는 토요일이며, 근태 상태(LEAVE)와 결합해 휴일 사용 위험으로 분류함.
2026-02-25 20:29:34,746 - core.analysis.analysis_pipeline - INFO - audit_analysis body_evidence: case_id=136 evidence_caseType=HOLIDAY_USAGE has_reasonText=True case_type=HOLIDAY_USAGE mccCode=5814 mccName=패스트푸드 evidence_keys=['evidence', 'ragRefs', 'doc_id', 'voucher_key', 'item_id', 'voucher_item_no', 'case_type', 'screening_reason_text', 'occurredAt', 'expenseType', 'merchantName', 'amount', 'hrStatus', 'mccCode', 'budgetExceeded', 'intended_risk_type', 'hrStatusRaw', 'isHoliday', 'mccName', 'risk_category']
2026-02-25 20:29:34,746 - core.analysis.analysis_pipeline - INFO - get_case response case_id=136 belnr=D28822B documentNumber=None amount=48244.0 totalAmount=None
2026-02-25 20:29:34,746 - core.analysis.analysis_pipeline - INFO - audit_analysis case_identifiers case_id=136 belnr_or_docNo=D28822B amount_used=48244.0
2026-02-25 20:29:35,047 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'INPUT_NORM', 'percent': 10}
2026-02-25 20:29:36,216 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM payload={'text_preview': '규정 조항 매칭을 진행 중이며, 조항 근거가 확인되면 상세 판단을 제시하겠습니다.'}
2026-02-25 20:29:36,216 - api.routes.aura_cases - INFO - agent_activity_log enqueue: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM message=규정 조항 매칭을 진행 중이며, 조항 근거가 확인되면 상세 판단을 제시하겠습니다.
2026-02-25 20:29:36,233 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'EVIDENCE_GATHER', 'percent': 25}
2026-02-25 20:29:36,234 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM payload={'text_preview': '규정 조항 매칭을 진행 중이며, 조항 근거가 확인되면 상세 판단을 제시하겠습니다.'}
2026-02-25 20:29:36,436 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'EVIDENCE_GATHER', 'percent': 25}
2026-02-25 20:29:37,668 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'REGULATION_MATCH', 'percent': 35}
2026-02-25 20:29:37,671 - core.analysis.analysis_pipeline - INFO - analysis_pipeline: RAG query start case_id=136 query=법인카드 전표 규정 감사 준수 휴일 주말 공휴일 심야 업무연관성 SA 발생일 2026-03-21 금액 48244.0 근태 LEAVE MCC 5814 tenant_id=1 doc_ids=[23, 26] metadata_filter=None
2026-02-25 20:29:39,668 - core.analysis.rag - INFO - [RAG Search] tenant: 1, doc_count: 2, threshold: 0.75
2026-02-25 20:29:39,833 - core.analysis.analysis_pipeline - INFO - analysis_pipeline: RAG raw_result case_id=136 count=0 max_score=0.000
2026-02-25 20:29:39,833 - core.analysis.analysis_pipeline - INFO - analysis_pipeline: RAG retry start case_id=136 mode=compact_query threshold=0.60 query=법인카드 전표 규정 휴일 주말 공휴일 심야 2026-03-21 LEAVE MCC 5814 휴일
2026-02-25 20:29:40,158 - core.analysis.rag - INFO - [RAG Search] tenant: 1, doc_count: 2, threshold: 0.6
2026-02-25 20:29:40,166 - core.analysis.analysis_pipeline - INFO - analysis_pipeline: RAG retry empty case_id=136
2026-02-25 20:29:40,166 - core.analysis.analysis_pipeline - INFO - analysis_pipeline: RAG constraints case_id=136 mcc=None articles=[] index_version=None effective_date=2026-03-21
2026-02-25 20:29:40,166 - core.analysis.analysis_pipeline - INFO - analysis_pipeline: RAG summary case_id=136 results=0 max_score=0.000 threshold=0.700 need_web_search=True reasons=['RAG_RESULT_EMPTY', 'RETRY_COMPACT_QUERY_FAILED'] raw_count=0 raw_max=0.000 rule_filtered_count=0 retried=True retry_threshold=0.6
2026-02-25 20:29:40,167 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'WEB_SEARCH', 'percent': 38}
2026-02-25 20:29:40,315 - core.analysis.analysis_pipeline - INFO - analysis_pipeline: web_search completed case_id=136 query=법인카드 SA 세무처리 국세청 가이드라인 회계기준 text_len=71 citations=0
2026-02-25 20:29:40,315 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'REGULATION_MATCH', 'percent': 35}
2026-02-25 20:29:40,466 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'WEB_SEARCH', 'percent': 38}
2026-02-25 20:29:40,503 - core.analysis.analysis_pipeline - INFO - case=136 using body.evidence fallback (12 items, only_case_evidence=True)
2026-02-25 20:29:41,942 - core.analysis.thought_stream - INFO - thought_stream downgraded: reason=no_rag_strong_claim text=**48244원**의 지출이 **2026-03-21 00:06**에 발생한 점을 고려할 때, **최우선 가이드**인 **휴일 사용 의심**에 대한 세부 조사를 통해 위반 여부를 규명할 필요가 있습니다.
2026-02-25 20:29:41,942 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM payload={'text_preview': '현재는 규정 근거가 충분하지 않아 사실 확인 범위를 확정할 수 없습니다. 근거 확보 후 판단을 업데이트하겠습니다.'}
2026-02-25 20:29:41,942 - api.routes.aura_cases - INFO - agent_activity_log enqueue: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM message=현재는 규정 근거가 충분하지 않아 사실 확인 범위를 확정할 수 없습니다. 근거 확보 후 판단을 업데이트하겠습니다.
2026-02-25 20:29:41,943 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM payload={'text_preview': '현재는 규정 근거가 충분하지 않아 사실 확인 범위를 확정할 수 없습니다. 근거 확보 후 판단을 업데이트하겠습니다.'}
2026-02-25 20:29:43,010 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM payload={'text_preview': '규정 조항 매칭을 진행 중이며, 조항 근거가 확인되면 상세 판단을 제시하겠습니다.'}
2026-02-25 20:29:43,010 - api.routes.aura_cases - INFO - agent_activity_log enqueue: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM message=규정 조항 매칭을 진행 중이며, 조항 근거가 확인되면 상세 판단을 제시하겠습니다.
2026-02-25 20:29:43,011 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'RULE_SCORING', 'percent': 45}
2026-02-25 20:29:43,011 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM payload={'text_preview': '규정 조항 매칭을 진행 중이며, 조항 근거가 확인되면 상세 판단을 제시하겠습니다.'}
2026-02-25 20:29:43,162 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'RULE_SCORING', 'percent': 45}
2026-02-25 20:29:44,259 - core.analysis.thought_stream - INFO - thought_stream downgraded: reason=no_rag_strong_claim text=**48244원**의 심야 식대 지출이 **제1항**의 경비 한도 규정을 위반할 소지가 크므로, 해당 건의 업무 관련성을 면밀히 검토할 필요가 있습니다.
2026-02-25 20:29:44,259 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM payload={'text_preview': '현재는 규정 근거가 충분하지 않아 사실 확인 범위를 확정할 수 없습니다. 근거 확보 후 판단을 업데이트하겠습니다.'}
2026-02-25 20:29:44,259 - api.routes.aura_cases - INFO - agent_activity_log enqueue: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM message=현재는 규정 근거가 충분하지 않아 사실 확인 범위를 확정할 수 없습니다. 근거 확보 후 판단을 업데이트하겠습니다.
2026-02-25 20:29:44,259 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'LLM_REASONING', 'percent': 65}
2026-02-25 20:29:44,259 - core.analysis.analysis_pipeline - INFO - audit_analysis LLM reasonText input: case_id=136 risk_type=HOLIDAY_USAGE screening_case_type=None has_screening_reason_text=False prompt_guide=intended_risk=True screening_type=False reason_only=False
2026-02-25 20:29:44,338 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=AGENT_STREAM payload={'text_preview': '현재는 규정 근거가 충분하지 않아 사실 확인 범위를 확정할 수 없습니다. 근거 확보 후 판단을 업데이트하겠습니다.'}
2026-02-25 20:29:44,489 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'LLM_REASONING', 'percent': 65}
2026-02-25 20:29:50,048 - core.analysis.analysis_pipeline - INFO - audit_analysis reasonText resolved: case_id=136 preview=이번 분석에서는 '휴일 사용 의심' 위험 유형에 대해 명확한 규정 근거가 없어 최종 판단을 보류합니다. 해당 거래는 2026년 3월 21일에 발생한 법인카드 사용으로, 금액은 48,244원이었으며 경비 유형은 SA로…
2026-02-25 20:29:50,048 - core.analysis.thought_stream - INFO - thought_stream downgraded: reason=no_rag_strong_claim text=이번 분석에서는 '휴일 사용 의심' 위험 유형에 대해 명확한 규정 근거가 없어 최종 판단을 보류합니다. 해당 거래는 2026년 3월 21일에 발생한 법인카드 사용으로, 금액은 48,244원이었으며 경비 유형은 SA로 분류되었습니다. 내부 규정(doc_…
2026-02-25 20:29:50,048 - core.analysis.analysis_pipeline - INFO - audit_analysis quality_gate case_id=136 codes=['RAG_ZERO'] policy_signals=['holiday_usage', 'leave_status', 'leave_general']
2026-02-25 20:29:50,048 - core.analysis.analysis_pipeline - INFO - audit_analysis conservative scoring applied: case_id=136 reason=RAG_ZERO original=0.589 adjusted=0.350
2026-02-25 20:29:50,048 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'PROPOSALS', 'percent': 85}
2026-02-25 20:29:50,049 - api.routes.aura_cases - INFO - analysis_background event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=completed payload={'runId': '7c115b35-3001-467b-a3d5-5f9fbf1a0ac2', 'caseId': '136', 'severity': 'LOW', 'score': 0.35, 'status': 'completed', 'text_preview': '현재는 규정 근거가 충분하지 않아 사실 확인 범위를 확정할 수 없습니다. 근거 확보 후 판단을 업데이트하겠습니다.'}
2026-02-25 20:29:50,049 - core.analysis.callback_client - INFO - Callback sending: url=http://localhost:8080/api/synapse/internal/aura/callback payload_keys=['runId', 'caseId', 'status', 'agent_id', 'version', 'is_sandbox', 'trace', 'finalResult'] summary={'runId': '7c115b35-3001-467b-a3d5-5f9fbf1a0ac2', 'caseId': '136', 'status': 'COMPLETED', 'agent_id': 'finance_aura', 'version': '6', 'is_sandbox': False, 'trace': {'auraTraceId': 'aura-7c115b35-01027525'}, 'finalResult': '<dict>'}
2026-02-25 20:29:50,100 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=step payload={'label': 'PROPOSALS', 'percent': 85}
2026-02-25 20:29:50,214 - core.analysis.callback_client - INFO - Callback ok url=http://localhost:8080/api/synapse/internal/aura/callback status_code=200
2026-02-25 20:29:50,318 - core.analysis.callback - INFO - case status updated case_id=136 status=NEW
2026-02-25 20:29:50,426 - api.routes.aura_cases - INFO - case_analysis_stream SSE event: run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2 case_id=136 event=completed payload={'runId': '7c115b35-3001-467b-a3d5-5f9fbf1a0ac2', 'caseId': '136', 'severity': 'LOW', 'score': 0.35, 'status': 'completed', 'text_preview': '현재는 규정 근거가 충분하지 않아 사실 확인 범위를 확정할 수 없습니다. 근거 확보 후 판단을 업데이트하겠습니다.'}
2026-02-25 20:29:52,320 - core.analysis.run_store - INFO - run_store: queue removed run_id=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2


## Analysis - 2026-02-25 Aura Log (caseId=136, runId=7c115b35-3001-467b-a3d5-5f9fbf1a0ac2)

### Confirmed Findings
- `expenseType`가 `SA` 코드값으로 전달되어 Aura RAG query에 그대로 사용됨.
  - 증거: `analysis_pipeline: RAG query ... 경비 유형은 SA ...`
- `mccCode=5814`는 payload-first로 정상 전달됨.
  - 증거: `audit_analysis payload-first ... mccCode=5814 ...`
- 하지만 RAG constraint 단계에서 `mcc=None`으로 처리됨.
  - 증거: `analysis_pipeline: RAG constraints ... mcc=None ...`
- 결과적으로 RAG 결과 0건 (`RAG_ZERO`) 및 보수 스코어링으로 종료.

### Interpretation
- 문제 1(ExpenseType): BE가 코드값만 전달하고 있어 Aura 검색어 의미가 약함.
- 문제 2(MCC Rule Link): BE에서 MCC는 전달되지만 Aura 규칙 매핑/로딩 경로에서 5814가 반영되지 않음(또는 룰셋 미존재).

### BE-side Action Candidates
- expenseType 보강
  - `expenseType` 유지 + `expenseTypeName`(사람 읽기용) 추가 전달 권장.
  - 후보 소스: 코드 마스터 매핑 또는 문맥형 별칭(예: 식대/교통비 등).
- MCC 규정 컨텍스트 보강
  - 이미 전달 중인 `mccCode/mccName/risk_category/is_weekend_allowed` 외에
  - `mcc_related_article`(mcc_master.related_article) 추가 전달 권장.
  - Aura가 rule-link 조인 실패 시에도 payload만으로 최소 규정 힌트 확보 가능.

### Current Status
- payload-first 경로는 정상 동작.
- `skip get_case fallback` 로그 확인됨.
- 다만 Aura 내부에서 식별용 `get_case response` 로그는 별도로 존재(완전 미호출과는 다름).
