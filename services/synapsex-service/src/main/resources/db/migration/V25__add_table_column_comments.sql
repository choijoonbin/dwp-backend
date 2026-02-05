-- ======================================================================
-- 테이블/컬럼 코멘트 현행화
-- fi_doc_header, fi_doc_item, fi_open_item, bp_party, agent_case, agent_action 등
-- 누락된 COMMENT ON 추가. 재기동 시 Flyway가 적용.
-- ======================================================================

SET search_path TO dwp_aura, public;

-- ----------------------------------------------------------------------
-- sap_raw_events
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.sap_raw_events IS 'SAP 원천 Raw 이벤트. 재처리/감사용. 적재 파이프라인 입력.';
COMMENT ON COLUMN dwp_aura.sap_raw_events.id IS 'Raw 이벤트 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.sap_raw_events.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN dwp_aura.sap_raw_events.source_system IS '원천 시스템 (SAP_ECC, S4HANA 등)';
COMMENT ON COLUMN dwp_aura.sap_raw_events.interface_name IS '인터페이스명 (예: FI_DOCUMENT, FI_OPEN_ITEM)';
COMMENT ON COLUMN dwp_aura.sap_raw_events.extract_date IS '추출 일자';
COMMENT ON COLUMN dwp_aura.sap_raw_events.payload_format IS '페이로드 형식 (JSON, XML 등)';
COMMENT ON COLUMN dwp_aura.sap_raw_events.s3_object_key IS 'S3 객체 키 (선택)';
COMMENT ON COLUMN dwp_aura.sap_raw_events.payload_json IS '원본 페이로드 (JSONB)';
COMMENT ON COLUMN dwp_aura.sap_raw_events.checksum IS '중복 방지 체크섬';
COMMENT ON COLUMN dwp_aura.sap_raw_events.status IS '상태 (RECEIVED, PROCESSED, FAILED)';
COMMENT ON COLUMN dwp_aura.sap_raw_events.error_message IS '오류 메시지 (실패 시)';
COMMENT ON COLUMN dwp_aura.sap_raw_events.created_at IS '수신 일시';

-- ----------------------------------------------------------------------
-- ingestion_errors
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.ingestion_errors IS '적재 오류 로그. Raw 이벤트 처리 실패 시 기록.';
COMMENT ON COLUMN dwp_aura.ingestion_errors.id IS '오류 로그 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.ingestion_errors.raw_event_id IS '원본 Raw 이벤트 ID (FK: sap_raw_events.id)';
COMMENT ON COLUMN dwp_aura.ingestion_errors.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.ingestion_errors.dataset_id IS '데이터셋 ID (fi_doc_header, fi_open_item 등)';
COMMENT ON COLUMN dwp_aura.ingestion_errors.record_key IS '레코드 키 (적재 실패 레코드 식별)';
COMMENT ON COLUMN dwp_aura.ingestion_errors.error_code IS '오류 코드';
COMMENT ON COLUMN dwp_aura.ingestion_errors.error_detail IS '오류 상세';
COMMENT ON COLUMN dwp_aura.ingestion_errors.record_json IS '실패 레코드 원본 (JSONB)';
COMMENT ON COLUMN dwp_aura.ingestion_errors.created_at IS '발생 일시';

-- ----------------------------------------------------------------------
-- fi_doc_header
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.fi_doc_header IS 'FI 전표 헤더 (Canonical). SAP ECC/S4 전표 원천.';
COMMENT ON COLUMN dwp_aura.fi_doc_header.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.fi_doc_header.bukrs IS '회사코드 (SAP BUKRS)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.belnr IS '전표번호 (SAP BELNR)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.gjahr IS '회계연도 (SAP GJAHR)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.doc_source IS '전표 원천 (SAP, MANUAL 등)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.budat IS '전기일 (Posting Date)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.bldat IS '증빙일 (Document Date)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.cpudt IS '처리일 (CPU Date)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.cputm IS '처리시간 (CPU Time)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.usnam IS '생성자 (User Name)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.tcode IS '트랜잭션 코드 (SAP TCODE)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.blart IS '전표유형 (Document Type)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.waers IS '통화 (Currency)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.kursf IS '환율';
COMMENT ON COLUMN dwp_aura.fi_doc_header.xblnr IS '참조번호 (External Document No)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.bktxt IS '헤더텍스트';
COMMENT ON COLUMN dwp_aura.fi_doc_header.status_code IS '전표 상태 (POSTED, PARKED 등)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.reversal_belnr IS '역분개 전표번호';
COMMENT ON COLUMN dwp_aura.fi_doc_header.last_change_ts IS '마지막 변경 시각';
COMMENT ON COLUMN dwp_aura.fi_doc_header.raw_event_id IS '원천 Raw 이벤트 ID (FK)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.created_at IS '생성일시 (Detect 배치 윈도우 기준)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- fi_doc_item
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.fi_doc_item IS 'FI 전표 라인 (Canonical). fi_doc_header 자식.';
COMMENT ON COLUMN dwp_aura.fi_doc_item.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.fi_doc_item.bukrs IS '회사코드';
COMMENT ON COLUMN dwp_aura.fi_doc_item.belnr IS '전표번호';
COMMENT ON COLUMN dwp_aura.fi_doc_item.gjahr IS '회계연도';
COMMENT ON COLUMN dwp_aura.fi_doc_item.buzei IS '라인번호 (Line Item)';
COMMENT ON COLUMN dwp_aura.fi_doc_item.hkont IS '계정 (G/L Account)';
COMMENT ON COLUMN dwp_aura.fi_doc_item.bschl IS '전기키 (Posting Key)';
COMMENT ON COLUMN dwp_aura.fi_doc_item.shkzg IS '차대구분 (S=차변, H=대변)';
COMMENT ON COLUMN dwp_aura.fi_doc_item.lifnr IS '공급업체코드 (Vendor)';
COMMENT ON COLUMN dwp_aura.fi_doc_item.kunnr IS '고객코드 (Customer)';
COMMENT ON COLUMN dwp_aura.fi_doc_item.wrbtr IS '금액 (Transaction Currency)';
COMMENT ON COLUMN dwp_aura.fi_doc_item.dmbtr IS '로컬통화 금액';
COMMENT ON COLUMN dwp_aura.fi_doc_item.waers IS '통화';
COMMENT ON COLUMN dwp_aura.fi_doc_item.mwskz IS '부가세코드';
COMMENT ON COLUMN dwp_aura.fi_doc_item.kostl IS '코스트센터';
COMMENT ON COLUMN dwp_aura.fi_doc_item.prctr IS '손익센터';
COMMENT ON COLUMN dwp_aura.fi_doc_item.aufnr IS '오더번호';
COMMENT ON COLUMN dwp_aura.fi_doc_item.zterm IS '지급조건';
COMMENT ON COLUMN dwp_aura.fi_doc_item.zfbdt IS '기본만기일';
COMMENT ON COLUMN dwp_aura.fi_doc_item.due_date IS '만기일';
COMMENT ON COLUMN dwp_aura.fi_doc_item.payment_block IS '지급블록 여부';
COMMENT ON COLUMN dwp_aura.fi_doc_item.dispute_flag IS '분쟁 플래그';
COMMENT ON COLUMN dwp_aura.fi_doc_item.zuonr IS '할당번호';
COMMENT ON COLUMN dwp_aura.fi_doc_item.sgtxt IS '라인텍스트';
COMMENT ON COLUMN dwp_aura.fi_doc_item.last_change_ts IS '마지막 변경 시각';
COMMENT ON COLUMN dwp_aura.fi_doc_item.raw_event_id IS '원천 Raw 이벤트 ID (FK)';
COMMENT ON COLUMN dwp_aura.fi_doc_item.created_at IS '생성일시';

-- ----------------------------------------------------------------------
-- fi_open_item
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.fi_open_item IS 'FI 미결항목 (AP/AR). Open Item Management.';
COMMENT ON COLUMN dwp_aura.fi_open_item.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.fi_open_item.bukrs IS '회사코드';
COMMENT ON COLUMN dwp_aura.fi_open_item.belnr IS '전표번호';
COMMENT ON COLUMN dwp_aura.fi_open_item.gjahr IS '회계연도';
COMMENT ON COLUMN dwp_aura.fi_open_item.buzei IS '라인번호';
COMMENT ON COLUMN dwp_aura.fi_open_item.item_type IS '유형 (AP=매입채무, AR=매출채권)';
COMMENT ON COLUMN dwp_aura.fi_open_item.lifnr IS '공급업체코드';
COMMENT ON COLUMN dwp_aura.fi_open_item.kunnr IS '고객코드';
COMMENT ON COLUMN dwp_aura.fi_open_item.baseline_date IS '기준일';
COMMENT ON COLUMN dwp_aura.fi_open_item.zterm IS '지급조건';
COMMENT ON COLUMN dwp_aura.fi_open_item.due_date IS '만기일';
COMMENT ON COLUMN dwp_aura.fi_open_item.open_amount IS '미결금액';
COMMENT ON COLUMN dwp_aura.fi_open_item.currency IS '통화';
COMMENT ON COLUMN dwp_aura.fi_open_item.cleared IS '청산 여부';
COMMENT ON COLUMN dwp_aura.fi_open_item.clearing_date IS '청산일';
COMMENT ON COLUMN dwp_aura.fi_open_item.payment_block IS '지급블록 여부';
COMMENT ON COLUMN dwp_aura.fi_open_item.dispute_flag IS '분쟁 플래그';
COMMENT ON COLUMN dwp_aura.fi_open_item.last_change_ts IS '마지막 변경 시각';
COMMENT ON COLUMN dwp_aura.fi_open_item.raw_event_id IS '원천 Raw 이벤트 ID (FK)';
COMMENT ON COLUMN dwp_aura.fi_open_item.last_update_ts IS '마지막 업데이트 시각 (Detect 배치 윈도우 기준)';

-- ----------------------------------------------------------------------
-- bp_party
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.bp_party IS '거래처 마스터 (Business Partner). VENDOR/CUSTOMER.';
COMMENT ON COLUMN dwp_aura.bp_party.party_id IS '거래처 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.bp_party.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.bp_party.party_type IS '유형 (VENDOR=공급업체, CUSTOMER=고객)';
COMMENT ON COLUMN dwp_aura.bp_party.party_code IS '거래처코드 (lifnr/kunnr)';
COMMENT ON COLUMN dwp_aura.bp_party.name_display IS '표시명';
COMMENT ON COLUMN dwp_aura.bp_party.country IS '국가코드 (3자리, ISO 3166-1 alpha-3)';
COMMENT ON COLUMN dwp_aura.bp_party.created_on IS '생성일';
COMMENT ON COLUMN dwp_aura.bp_party.is_one_time IS '일회성 거래처 여부';
COMMENT ON COLUMN dwp_aura.bp_party.risk_flags IS '리스크 플래그 (JSONB, score 등)';
COMMENT ON COLUMN dwp_aura.bp_party.last_change_ts IS '마지막 변경 시각';
COMMENT ON COLUMN dwp_aura.bp_party.raw_event_id IS '원천 Raw 이벤트 ID (FK)';
COMMENT ON COLUMN dwp_aura.bp_party.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- bp_party_pii_vault
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.bp_party_pii_vault IS '거래처 PII 암호화 저장소. 개인정보 암호화/해시.';
COMMENT ON COLUMN dwp_aura.bp_party_pii_vault.party_id IS '거래처 ID (PK, FK: bp_party.party_id)';
COMMENT ON COLUMN dwp_aura.bp_party_pii_vault.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.bp_party_pii_vault.pii_cipher IS '암호화된 PII (BYTEA)';
COMMENT ON COLUMN dwp_aura.bp_party_pii_vault.pii_hash IS 'PII 해시 (검색용)';
COMMENT ON COLUMN dwp_aura.bp_party_pii_vault.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- sap_change_log
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.sap_change_log IS 'SAP 변경 이력 (CDHDR/CDPOS 유사). 객체별 변경 추적.';
COMMENT ON COLUMN dwp_aura.sap_change_log.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.sap_change_log.objectclas IS '객체 클래스';
COMMENT ON COLUMN dwp_aura.sap_change_log.objectid IS '객체 ID (party_code 등)';
COMMENT ON COLUMN dwp_aura.sap_change_log.changenr IS '변경번호';
COMMENT ON COLUMN dwp_aura.sap_change_log.username IS '변경 사용자';
COMMENT ON COLUMN dwp_aura.sap_change_log.udate IS '변경일';
COMMENT ON COLUMN dwp_aura.sap_change_log.utime IS '변경시간';
COMMENT ON COLUMN dwp_aura.sap_change_log.tabname IS '테이블명';
COMMENT ON COLUMN dwp_aura.sap_change_log.fname IS '필드명';
COMMENT ON COLUMN dwp_aura.sap_change_log.value_old IS '변경 전 값';
COMMENT ON COLUMN dwp_aura.sap_change_log.value_new IS '변경 후 값';
COMMENT ON COLUMN dwp_aura.sap_change_log.last_change_ts IS '마지막 변경 시각';
COMMENT ON COLUMN dwp_aura.sap_change_log.raw_event_id IS '원천 Raw 이벤트 ID (FK)';

-- ----------------------------------------------------------------------
-- agent_case
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.agent_case IS '에이전트 케이스. Detect 배치/룰 탐지 결과.';
COMMENT ON COLUMN dwp_aura.agent_case.case_id IS '케이스 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.agent_case.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.agent_case.detected_at IS '탐지 시각';
COMMENT ON COLUMN dwp_aura.agent_case.bukrs IS '회사코드 (전표/오픈아이템 연결)';
COMMENT ON COLUMN dwp_aura.agent_case.belnr IS '전표번호';
COMMENT ON COLUMN dwp_aura.agent_case.gjahr IS '회계연도';
COMMENT ON COLUMN dwp_aura.agent_case.buzei IS '라인번호';
COMMENT ON COLUMN dwp_aura.agent_case.case_type IS '케이스 유형 (DUPLICATE_INVOICE, ANOMALY_AMOUNT 등)';
COMMENT ON COLUMN dwp_aura.agent_case.severity IS '심각도 (LOW, MEDIUM, HIGH)';
COMMENT ON COLUMN dwp_aura.agent_case.score IS '리스크 점수';
COMMENT ON COLUMN dwp_aura.agent_case.reason_text IS '탐지 사유 텍스트';
COMMENT ON COLUMN dwp_aura.agent_case.evidence_json IS '증거 데이터 (JSONB)';
COMMENT ON COLUMN dwp_aura.agent_case.rag_refs_json IS 'RAG 참조 (JSONB)';
COMMENT ON COLUMN dwp_aura.agent_case.status IS '상태 (OPEN, IN_REVIEW, APPROVED, REJECTED, ACTIONED, CLOSED, TRIAGED, IN_PROGRESS, RESOLVED, DISMISSED)';
COMMENT ON COLUMN dwp_aura.agent_case.owner_user IS '담당자 (User ID)';
COMMENT ON COLUMN dwp_aura.agent_case.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.agent_case.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- agent_action
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.agent_action IS '에이전트 액션. 케이스별 제안/승인/실행.';
COMMENT ON COLUMN dwp_aura.agent_action.action_id IS '액션 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.agent_action.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.agent_action.case_id IS '케이스 ID (FK: agent_case.case_id)';
COMMENT ON COLUMN dwp_aura.agent_action.action_type IS '액션 유형 (PAYMENT_BLOCK, SEND_NUDGE 등)';
COMMENT ON COLUMN dwp_aura.agent_action.action_payload IS '액션 페이로드 (JSONB)';
COMMENT ON COLUMN dwp_aura.agent_action.planned_at IS '계획 시각';
COMMENT ON COLUMN dwp_aura.agent_action.executed_at IS '실행 시각';
COMMENT ON COLUMN dwp_aura.agent_action.status IS '상태 (PLANNED, PROPOSED, PENDING_APPROVAL, APPROVED, EXECUTING, EXECUTED, FAILED, CANCELED 등)';
COMMENT ON COLUMN dwp_aura.agent_action.executed_by IS '실행자 (PENDING, USER_ID 등)';
COMMENT ON COLUMN dwp_aura.agent_action.error_message IS '오류 메시지';
COMMENT ON COLUMN dwp_aura.agent_action.requested_by_user_id IS '요청자 user_id';
COMMENT ON COLUMN dwp_aura.agent_action.requested_by_actor_type IS '요청자 유형 (USER, AGENT, SYSTEM)';
COMMENT ON COLUMN dwp_aura.agent_action.payload_json IS '페이로드 JSON';
COMMENT ON COLUMN dwp_aura.agent_action.simulation_before IS '시뮬레이션 전 상태';
COMMENT ON COLUMN dwp_aura.agent_action.simulation_after IS '시뮬레이션 후 상태';
COMMENT ON COLUMN dwp_aura.agent_action.diff_json IS '변경 diff';
COMMENT ON COLUMN dwp_aura.agent_action.failure_reason IS '실패 사유';
COMMENT ON COLUMN dwp_aura.agent_action.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.agent_action.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- integration_outbox
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.integration_outbox IS '통합 아웃박스. 외부 시스템 전송 대기 이벤트.';
COMMENT ON COLUMN dwp_aura.integration_outbox.outbox_id IS '아웃박스 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.integration_outbox.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.integration_outbox.target_system IS '대상 시스템 (SAP, AURA 등)';
COMMENT ON COLUMN dwp_aura.integration_outbox.event_type IS '이벤트 유형';
COMMENT ON COLUMN dwp_aura.integration_outbox.event_key IS '이벤트 키 (중복 방지)';
COMMENT ON COLUMN dwp_aura.integration_outbox.payload IS '페이로드 (JSONB)';
COMMENT ON COLUMN dwp_aura.integration_outbox.status IS '상태 (PENDING, SENT, FAILED)';
COMMENT ON COLUMN dwp_aura.integration_outbox.retry_count IS '재시도 횟수';
COMMENT ON COLUMN dwp_aura.integration_outbox.next_retry_at IS '다음 재시도 시각';
COMMENT ON COLUMN dwp_aura.integration_outbox.last_error IS '마지막 오류';
COMMENT ON COLUMN dwp_aura.integration_outbox.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.integration_outbox.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- policy_doc_metadata
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.policy_doc_metadata IS '정책 문서 메타데이터. RAG/정책 문서 참조.';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.doc_id IS '문서 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.policy_id IS '정책 ID';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.category IS '카테고리';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.effective_date IS '시행일';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.priority IS '우선순위';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.title IS '제목';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.content_hash IS '내용 해시';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.source_uri IS '원본 URI';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.policy_doc_metadata.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- detect_run (추가 컬럼 코멘트)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.detect_run.run_id IS '실행 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.detect_run.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.detect_run.window_from IS '탐지 윈도우 시작';
COMMENT ON COLUMN dwp_aura.detect_run.window_to IS '탐지 윈도우 종료';
COMMENT ON COLUMN dwp_aura.detect_run.error_message IS '오류 메시지 (실패 시)';
COMMENT ON COLUMN dwp_aura.detect_run.started_at IS '시작 시각';
COMMENT ON COLUMN dwp_aura.detect_run.completed_at IS '완료 시각';

-- ----------------------------------------------------------------------
-- ingest_run (추가 컬럼 코멘트)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.ingest_run.run_id IS '실행 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.ingest_run.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.ingest_run.batch_id IS '배치 ID';
COMMENT ON COLUMN dwp_aura.ingest_run.window_from IS '적재 윈도우 시작';
COMMENT ON COLUMN dwp_aura.ingest_run.window_to IS '적재 윈도우 종료';
COMMENT ON COLUMN dwp_aura.ingest_run.error_message IS '오류 메시지 (실패 시)';
COMMENT ON COLUMN dwp_aura.ingest_run.started_at IS '시작 시각';
COMMENT ON COLUMN dwp_aura.ingest_run.completed_at IS '완료 시각';

-- ----------------------------------------------------------------------
-- audit_event_log (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.audit_event_log.audit_id IS '감사 로그 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.audit_event_log.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.audit_event_log.event_category IS '이벤트 카테고리 (CASE, ACTION, DETECT_RUN 등)';
COMMENT ON COLUMN dwp_aura.audit_event_log.event_type IS '이벤트 유형';
COMMENT ON COLUMN dwp_aura.audit_event_log.resource_type IS '대상 리소스 유형';
COMMENT ON COLUMN dwp_aura.audit_event_log.resource_id IS '대상 리소스 ID';
COMMENT ON COLUMN dwp_aura.audit_event_log.created_at IS '발생 일시';
COMMENT ON COLUMN dwp_aura.audit_event_log.actor_type IS '행위자 유형 (USER, AGENT, SYSTEM)';
COMMENT ON COLUMN dwp_aura.audit_event_log.actor_user_id IS '행위자 user_id';
COMMENT ON COLUMN dwp_aura.audit_event_log.actor_agent_id IS '행위자 agent_id';
COMMENT ON COLUMN dwp_aura.audit_event_log.actor_display_name IS '행위자 표시명';
COMMENT ON COLUMN dwp_aura.audit_event_log.channel IS '채널 (API, AGENT, BATCH 등)';
COMMENT ON COLUMN dwp_aura.audit_event_log.ip_address IS '요청 IP';
COMMENT ON COLUMN dwp_aura.audit_event_log.user_agent IS 'User-Agent';
COMMENT ON COLUMN dwp_aura.audit_event_log.outcome IS '결과 (SUCCESS, FAILED)';
COMMENT ON COLUMN dwp_aura.audit_event_log.severity IS '심각도 (INFO, WARN, ERROR)';
COMMENT ON COLUMN dwp_aura.audit_event_log.before_json IS '변경 전 (JSONB)';
COMMENT ON COLUMN dwp_aura.audit_event_log.after_json IS '변경 후 (JSONB)';
COMMENT ON COLUMN dwp_aura.audit_event_log.diff_json IS '변경 diff (JSONB)';
COMMENT ON COLUMN dwp_aura.audit_event_log.evidence_json IS '증거 (JSONB)';
COMMENT ON COLUMN dwp_aura.audit_event_log.tags IS '태그 (JSONB)';
COMMENT ON COLUMN dwp_aura.audit_event_log.gateway_request_id IS '게이트웨이 요청 ID';
COMMENT ON COLUMN dwp_aura.audit_event_log.trace_id IS '추적 ID';
COMMENT ON COLUMN dwp_aura.audit_event_log.span_id IS 'Span ID';

-- ----------------------------------------------------------------------
-- tenant_company_code_scope (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.tenant_company_code_scope.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.tenant_company_code_scope.bukrs IS '회사코드';
COMMENT ON COLUMN dwp_aura.tenant_company_code_scope.is_enabled IS '활성 여부';
COMMENT ON COLUMN dwp_aura.tenant_company_code_scope.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.tenant_company_code_scope.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- tenant_currency_scope (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.tenant_currency_scope.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.tenant_currency_scope.waers IS '통화 코드';
COMMENT ON COLUMN dwp_aura.tenant_currency_scope.is_enabled IS '활성 여부';
COMMENT ON COLUMN dwp_aura.tenant_currency_scope.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.tenant_currency_scope.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- tenant_sod_rule (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.rule_id IS '규칙 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.rule_key IS '규칙 키';
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.title IS '규칙 제목';
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.description IS '규칙 설명';
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.is_enabled IS '활성 여부';
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.applies_to IS '적용 대상 액션 목록 (JSONB)';
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- tenant_scope_seed_state
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.tenant_scope_seed_state IS 'Tenant Scope 시드 완료 상태. 첫 GET 시 idempotent 시드용.';
COMMENT ON COLUMN dwp_aura.tenant_scope_seed_state.tenant_id IS '테넌트 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.tenant_scope_seed_state.seeded_at IS '시드 실행 시각';
COMMENT ON COLUMN dwp_aura.tenant_scope_seed_state.seed_version IS '시드 버전';

-- ----------------------------------------------------------------------
-- policy_data_protection (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.policy_data_protection.protection_id IS '보호 정책 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.policy_data_protection.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.policy_data_protection.profile_id IS '프로파일 ID (FK)';
COMMENT ON COLUMN dwp_aura.policy_data_protection.at_rest_encryption_enabled IS '저장 시 암호화 여부';
COMMENT ON COLUMN dwp_aura.policy_data_protection.audit_retention_years IS '감사 보존 연수';
COMMENT ON COLUMN dwp_aura.policy_data_protection.export_requires_approval IS '내보내기 승인 필수 여부';
COMMENT ON COLUMN dwp_aura.policy_data_protection.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.policy_data_protection.kms_mode IS 'KMS 모드';

-- ----------------------------------------------------------------------
-- md_company_code (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.md_company_code.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.md_company_code.bukrs IS '회사코드';
COMMENT ON COLUMN dwp_aura.md_company_code.bukrs_name IS '회사명';
COMMENT ON COLUMN dwp_aura.md_company_code.country IS '국가코드';
COMMENT ON COLUMN dwp_aura.md_company_code.default_currency IS '기본 통화';
COMMENT ON COLUMN dwp_aura.md_company_code.is_active IS '활성 여부';
COMMENT ON COLUMN dwp_aura.md_company_code.source_system IS '원천 시스템';
COMMENT ON COLUMN dwp_aura.md_company_code.last_sync_ts IS '마지막 동기화 시각';
COMMENT ON COLUMN dwp_aura.md_company_code.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.md_company_code.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- md_currency (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.md_currency.currency_code IS '통화 코드 (PK)';
COMMENT ON COLUMN dwp_aura.md_currency.currency_name IS '통화명';
COMMENT ON COLUMN dwp_aura.md_currency.symbol IS '통화 기호';
COMMENT ON COLUMN dwp_aura.md_currency.minor_unit IS '소수 단위';
COMMENT ON COLUMN dwp_aura.md_currency.is_active IS '활성 여부';
COMMENT ON COLUMN dwp_aura.md_currency.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.md_currency.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- policy_scope_company
-- ----------------------------------------------------------------------
COMMENT ON TABLE dwp_aura.policy_scope_company IS 'Profile별 회사코드(BUKRS) 스코프. included=true면 scope 내.';
COMMENT ON COLUMN dwp_aura.policy_scope_company.scope_id IS '스코프 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.policy_scope_company.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.policy_scope_company.profile_id IS '프로파일 ID (FK)';
COMMENT ON COLUMN dwp_aura.policy_scope_company.bukrs IS '회사코드';
COMMENT ON COLUMN dwp_aura.policy_scope_company.included IS '스코프 포함 여부';
COMMENT ON COLUMN dwp_aura.policy_scope_company.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.policy_scope_company.created_by IS '생성자 user_id';
COMMENT ON COLUMN dwp_aura.policy_scope_company.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.policy_scope_company.updated_by IS '수정자 user_id';

-- ----------------------------------------------------------------------
-- policy_scope_currency (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.policy_scope_currency.scope_id IS '스코프 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.policy_scope_currency.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.policy_scope_currency.profile_id IS '프로파일 ID (FK)';
COMMENT ON COLUMN dwp_aura.policy_scope_currency.currency_code IS '통화 코드 (FK)';
COMMENT ON COLUMN dwp_aura.policy_scope_currency.included IS '스코프 포함 여부';
COMMENT ON COLUMN dwp_aura.policy_scope_currency.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.policy_scope_currency.created_by IS '생성자 user_id';
COMMENT ON COLUMN dwp_aura.policy_scope_currency.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.policy_scope_currency.updated_by IS '수정자 user_id';

-- ----------------------------------------------------------------------
-- policy_sod_rule (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.policy_sod_rule.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.policy_sod_rule.profile_id IS '프로파일 ID (FK)';
COMMENT ON COLUMN dwp_aura.policy_sod_rule.rule_key IS '규칙 키';
COMMENT ON COLUMN dwp_aura.policy_sod_rule.title IS '규칙 제목';
COMMENT ON COLUMN dwp_aura.policy_sod_rule.description IS '규칙 설명';
COMMENT ON COLUMN dwp_aura.policy_sod_rule.is_enabled IS '활성 여부';
COMMENT ON COLUMN dwp_aura.policy_sod_rule.config_json IS '규칙 설정 (JSONB)';
COMMENT ON COLUMN dwp_aura.policy_sod_rule.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.policy_sod_rule.created_by IS '생성자 user_id';
COMMENT ON COLUMN dwp_aura.policy_sod_rule.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.policy_sod_rule.updated_by IS '수정자 user_id';

-- ----------------------------------------------------------------------
-- rag_document (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.rag_document.doc_id IS '문서 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.rag_document.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.rag_document.title IS '문서 제목';
COMMENT ON COLUMN dwp_aura.rag_document.source_type IS '소스 유형 (UPLOAD, S3, URL)';
COMMENT ON COLUMN dwp_aura.rag_document.s3_key IS 'S3 객체 키';
COMMENT ON COLUMN dwp_aura.rag_document.url IS 'URL';
COMMENT ON COLUMN dwp_aura.rag_document.checksum IS '체크섬';
COMMENT ON COLUMN dwp_aura.rag_document.created_at IS '생성일시';

-- ----------------------------------------------------------------------
-- rag_chunk (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.rag_chunk.chunk_id IS '청크 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.rag_chunk.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.rag_chunk.doc_id IS '문서 ID (FK)';
COMMENT ON COLUMN dwp_aura.rag_chunk.page_no IS '페이지 번호';
COMMENT ON COLUMN dwp_aura.rag_chunk.chunk_text IS '청크 텍스트';
COMMENT ON COLUMN dwp_aura.rag_chunk.embedding_id IS '임베딩 ID (벡터 DB 연동)';
COMMENT ON COLUMN dwp_aura.rag_chunk.created_at IS '생성일시';

-- ----------------------------------------------------------------------
-- policy_guardrail (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.policy_guardrail.guardrail_id IS '가드레일 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.policy_guardrail.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.policy_guardrail.name IS '가드레일명';
COMMENT ON COLUMN dwp_aura.policy_guardrail.scope IS '적용 범위 (case_type, action_type 등)';
COMMENT ON COLUMN dwp_aura.policy_guardrail.rule_json IS '규칙 (JSONB)';
COMMENT ON COLUMN dwp_aura.policy_guardrail.is_enabled IS '활성 여부';
COMMENT ON COLUMN dwp_aura.policy_guardrail.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.policy_guardrail.updated_at IS '수정일시';

-- ----------------------------------------------------------------------
-- dictionary_term (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.dictionary_term.term_id IS '용어 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.dictionary_term.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.dictionary_term.term_key IS '용어 키';
COMMENT ON COLUMN dwp_aura.dictionary_term.label_ko IS '한글 라벨';
COMMENT ON COLUMN dwp_aura.dictionary_term.description IS '설명';
COMMENT ON COLUMN dwp_aura.dictionary_term.category IS '카테고리';
COMMENT ON COLUMN dwp_aura.dictionary_term.created_at IS '생성일시';

-- ----------------------------------------------------------------------
-- feedback_label (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.feedback_label.feedback_id IS '피드백 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.feedback_label.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.feedback_label.target_type IS '대상 유형 (CASE, DOC, ENTITY)';
COMMENT ON COLUMN dwp_aura.feedback_label.target_id IS '대상 ID';
COMMENT ON COLUMN dwp_aura.feedback_label.label IS '라벨 (VALID, INVALID, NEEDS_REVIEW)';
COMMENT ON COLUMN dwp_aura.feedback_label.comment IS '코멘트';
COMMENT ON COLUMN dwp_aura.feedback_label.created_by IS '생성자 user_id';
COMMENT ON COLUMN dwp_aura.feedback_label.created_at IS '생성일시';

-- ----------------------------------------------------------------------
-- recon_run (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.recon_run.run_id IS '실행 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.recon_run.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.recon_run.run_type IS '실행 유형 (DOC_OPENITEM_MATCH, ACTION_EFFECT 등)';
COMMENT ON COLUMN dwp_aura.recon_run.started_at IS '시작 시각';
COMMENT ON COLUMN dwp_aura.recon_run.ended_at IS '종료 시각';
COMMENT ON COLUMN dwp_aura.recon_run.summary_json IS '요약 (JSONB)';

-- ----------------------------------------------------------------------
-- recon_result (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.recon_result.result_id IS '결과 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.recon_result.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.recon_result.run_id IS '실행 ID (FK)';
COMMENT ON COLUMN dwp_aura.recon_result.resource_type IS '리소스 유형';
COMMENT ON COLUMN dwp_aura.recon_result.status IS '상태 (PASS, FAIL)';
COMMENT ON COLUMN dwp_aura.recon_result.detail_json IS '상세 (JSONB)';

-- ----------------------------------------------------------------------
-- analytics_kpi_daily (누락 컬럼)
-- ----------------------------------------------------------------------
COMMENT ON COLUMN dwp_aura.analytics_kpi_daily.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.analytics_kpi_daily.ymd IS '집계 일자';
COMMENT ON COLUMN dwp_aura.analytics_kpi_daily.metric_key IS '메트릭 키 (savings_estimate, prevented_loss 등)';
COMMENT ON COLUMN dwp_aura.analytics_kpi_daily.metric_value IS '메트릭 값';
COMMENT ON COLUMN dwp_aura.analytics_kpi_daily.dims_json IS '차원 (JSONB)';
COMMENT ON COLUMN dwp_aura.analytics_kpi_daily.dims_hash IS '차원 해시 (복합키)';
COMMENT ON COLUMN dwp_aura.analytics_kpi_daily.created_at IS '생성일시';
