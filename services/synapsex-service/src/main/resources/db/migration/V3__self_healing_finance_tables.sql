-- ======================================================================
-- Self-healing Finance (Agentic AI) - PostgreSQL DDL v1
-- 스키마: dwp_aura (DWP Aura Service 전용)
-- Target: Enterprise-grade, ECC/S/4(HANA) 통합 수용(Canonical Model)
-- Notes:
--   - 모든 SAP 원천(ECC 테이블 / S4 CDS/뷰/Universal Journal)은 Canonical 스키마로 수렴
--   - 적재 흐름: RAW(sap_raw_events) -> Canonical(fi_*, bp_*) -> Agent Case/Action/Audit
--   - 멀티테넌트/멀티회사/멀티통화 전제
-- ======================================================================

SET search_path TO dwp_aura, public;

-- ======================================================================
-- 0) 공통 타입 (dwp_aura 스키마에만 생성)
-- ======================================================================
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_type t
    JOIN pg_namespace n ON t.typnamespace = n.oid
    WHERE t.typname = 'agent_case_status' AND n.nspname = 'dwp_aura'
  ) THEN
    CREATE TYPE dwp_aura.agent_case_status AS ENUM ('OPEN','IN_REVIEW','APPROVED','REJECTED','ACTIONED','CLOSED');
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_type t
    JOIN pg_namespace n ON t.typnamespace = n.oid
    WHERE t.typname = 'agent_action_status' AND n.nspname = 'dwp_aura'
  ) THEN
    CREATE TYPE dwp_aura.agent_action_status AS ENUM ('PLANNED','SENT','SUCCESS','FAILED','CANCELLED');
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_type t
    JOIN pg_namespace n ON t.typnamespace = n.oid
    WHERE t.typname = 'open_item_type' AND n.nspname = 'dwp_aura'
  ) THEN
    CREATE TYPE dwp_aura.open_item_type AS ENUM ('AP','AR');
  END IF;
END $$;

-- ======================================================================
-- 1) RAW 이벤트(원본) 저장: 재처리/감사용
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.sap_raw_events (
  id              BIGSERIAL PRIMARY KEY,
  tenant_id       BIGINT NOT NULL,
  source_system   TEXT NOT NULL,
  interface_name  TEXT NOT NULL,
  extract_date    DATE NOT NULL,
  payload_format  TEXT NOT NULL,
  s3_object_key   TEXT,
  payload_json    JSONB,
  checksum        TEXT,
  status          TEXT NOT NULL DEFAULT 'RECEIVED',
  error_message   TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_sap_raw_events_idempotent
ON dwp_aura.sap_raw_events(tenant_id, source_system, interface_name, extract_date, checksum);

CREATE INDEX IF NOT EXISTS ix_sap_raw_events_extract_date
ON dwp_aura.sap_raw_events(tenant_id, interface_name, extract_date);

CREATE TABLE IF NOT EXISTS dwp_aura.ingestion_errors (
  id            BIGSERIAL PRIMARY KEY,
  raw_event_id  BIGINT REFERENCES dwp_aura.sap_raw_events(id) ON DELETE SET NULL,
  tenant_id     BIGINT NOT NULL,
  dataset_id    TEXT NOT NULL,
  record_key    TEXT,
  error_code    TEXT NOT NULL,
  error_detail  TEXT NOT NULL,
  record_json   JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_ingestion_errors_tenant_time
ON dwp_aura.ingestion_errors(tenant_id, created_at DESC);

-- ======================================================================
-- 2) Canonical: FI 전표 헤더/라인
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.fi_doc_header (
  tenant_id       BIGINT NOT NULL,
  bukrs           VARCHAR(4)  NOT NULL,
  belnr           VARCHAR(10) NOT NULL,
  gjahr           VARCHAR(4)  NOT NULL,
  doc_source      VARCHAR(10) NOT NULL,
  budat           DATE NOT NULL,
  bldat           DATE,
  cpudt           DATE,
  cputm           TIME,
  usnam           VARCHAR(12),
  tcode           VARCHAR(20),
  blart           VARCHAR(2),
  waers           VARCHAR(5),
  kursf           NUMERIC(18,6),
  xblnr           VARCHAR(30),
  bktxt           VARCHAR(200),
  status_code     VARCHAR(20),
  reversal_belnr  VARCHAR(10),
  last_change_ts  TIMESTAMPTZ,
  raw_event_id    BIGINT REFERENCES dwp_aura.sap_raw_events(id) ON DELETE SET NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, bukrs, belnr, gjahr)
);

CREATE INDEX IF NOT EXISTS ix_fi_doc_header_budat
ON dwp_aura.fi_doc_header(tenant_id, budat);

CREATE INDEX IF NOT EXISTS ix_fi_doc_header_xblnr
ON dwp_aura.fi_doc_header(tenant_id, xblnr);

CREATE INDEX IF NOT EXISTS ix_fi_doc_header_user_time
ON dwp_aura.fi_doc_header(tenant_id, usnam, cpudt);

CREATE TABLE IF NOT EXISTS dwp_aura.fi_doc_item (
  tenant_id       BIGINT NOT NULL,
  bukrs           VARCHAR(4)  NOT NULL,
  belnr           VARCHAR(10) NOT NULL,
  gjahr           VARCHAR(4)  NOT NULL,
  buzei           VARCHAR(3)  NOT NULL,
  hkont           VARCHAR(10) NOT NULL,
  bschl           VARCHAR(2),
  shkzg           VARCHAR(1),
  lifnr           VARCHAR(20),
  kunnr           VARCHAR(20),
  wrbtr           NUMERIC(18,2),
  dmbtr           NUMERIC(18,2),
  waers           VARCHAR(5),
  mwskz           VARCHAR(2),
  kostl           VARCHAR(10),
  prctr           VARCHAR(10),
  aufnr           VARCHAR(12),
  zterm           VARCHAR(4),
  zfbdt           DATE,
  due_date        DATE,
  payment_block   BOOLEAN NOT NULL DEFAULT false,
  dispute_flag    BOOLEAN NOT NULL DEFAULT false,
  zuonr           VARCHAR(18),
  sgtxt           VARCHAR(200),
  last_change_ts  TIMESTAMPTZ,
  raw_event_id    BIGINT REFERENCES dwp_aura.sap_raw_events(id) ON DELETE SET NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, bukrs, belnr, gjahr, buzei),
  FOREIGN KEY (tenant_id, bukrs, belnr, gjahr)
    REFERENCES dwp_aura.fi_doc_header(tenant_id, bukrs, belnr, gjahr)
    ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_fi_doc_item_partner
ON dwp_aura.fi_doc_item(tenant_id, lifnr, kunnr);

CREATE INDEX IF NOT EXISTS ix_fi_doc_item_hkont
ON dwp_aura.fi_doc_item(tenant_id, hkont);

CREATE INDEX IF NOT EXISTS ix_fi_doc_item_amount
ON dwp_aura.fi_doc_item(tenant_id, wrbtr);

-- ======================================================================
-- 3) Canonical: Open Items (AP/AR 통합)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.fi_open_item (
  tenant_id       BIGINT NOT NULL,
  bukrs           VARCHAR(4)  NOT NULL,
  belnr           VARCHAR(10) NOT NULL,
  gjahr           VARCHAR(4)  NOT NULL,
  buzei           VARCHAR(3)  NOT NULL,
  item_type       dwp_aura.open_item_type NOT NULL,
  lifnr           VARCHAR(20),
  kunnr           VARCHAR(20),
  baseline_date   DATE,
  zterm           VARCHAR(4),
  due_date        DATE NOT NULL,
  open_amount     NUMERIC(18,2) NOT NULL,
  currency        VARCHAR(5) NOT NULL,
  cleared         BOOLEAN NOT NULL DEFAULT false,
  clearing_date   DATE,
  payment_block   BOOLEAN NOT NULL DEFAULT false,
  dispute_flag    BOOLEAN NOT NULL DEFAULT false,
  last_change_ts  TIMESTAMPTZ,
  raw_event_id    BIGINT REFERENCES dwp_aura.sap_raw_events(id) ON DELETE SET NULL,
  last_update_ts  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, bukrs, belnr, gjahr, buzei)
);

CREATE INDEX IF NOT EXISTS ix_fi_open_item_due
ON dwp_aura.fi_open_item(tenant_id, due_date, cleared);

CREATE INDEX IF NOT EXISTS ix_fi_open_item_partner
ON dwp_aura.fi_open_item(tenant_id, item_type, lifnr, kunnr);

-- ======================================================================
-- 4) Canonical: Business Partner / 거래처 마스터 + PII Vault
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.bp_party (
  party_id      BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  party_type    VARCHAR(10) NOT NULL,
  party_code    VARCHAR(40) NOT NULL,
  name_display  VARCHAR(200),
  country       VARCHAR(3),
  created_on    DATE,
  is_one_time   BOOLEAN NOT NULL DEFAULT false,
  risk_flags    JSONB NOT NULL DEFAULT '{}'::jsonb,
  last_change_ts TIMESTAMPTZ,
  raw_event_id  BIGINT REFERENCES dwp_aura.sap_raw_events(id) ON DELETE SET NULL,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, party_type, party_code)
);

CREATE TABLE IF NOT EXISTS dwp_aura.bp_party_pii_vault (
  party_id     BIGINT PRIMARY KEY REFERENCES dwp_aura.bp_party(party_id) ON DELETE CASCADE,
  tenant_id    BIGINT NOT NULL,
  pii_cipher   BYTEA,
  pii_hash     TEXT,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_bp_party_code
ON dwp_aura.bp_party(tenant_id, party_type, party_code);

-- ======================================================================
-- 5) Change Log (CDHDR/CDPOS 등)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.sap_change_log (
  tenant_id     BIGINT NOT NULL,
  objectclas    VARCHAR(15) NOT NULL,
  objectid      VARCHAR(90) NOT NULL,
  changenr      VARCHAR(10) NOT NULL,
  username      VARCHAR(12),
  udate         DATE,
  utime         TIME,
  tabname       VARCHAR(30),
  fname         VARCHAR(30),
  value_old     TEXT,
  value_new     TEXT,
  last_change_ts TIMESTAMPTZ,
  raw_event_id   BIGINT REFERENCES dwp_aura.sap_raw_events(id) ON DELETE SET NULL,
  PRIMARY KEY (tenant_id, objectclas, objectid, changenr, tabname, fname)
);

CREATE INDEX IF NOT EXISTS ix_sap_change_log_obj
ON dwp_aura.sap_change_log(tenant_id, objectclas, objectid);

CREATE INDEX IF NOT EXISTS ix_sap_change_log_time
ON dwp_aura.sap_change_log(tenant_id, udate, utime);

-- ======================================================================
-- 6) Agent Case / Action / Audit
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.agent_case (
  case_id        BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  detected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  bukrs          VARCHAR(4),
  belnr          VARCHAR(10),
  gjahr          VARCHAR(4),
  buzei          VARCHAR(3),
  case_type      VARCHAR(50) NOT NULL,
  severity       VARCHAR(10) NOT NULL,
  score          NUMERIC(6,4),
  reason_text    TEXT,
  evidence_json  JSONB,
  rag_refs_json  JSONB,
  status         dwp_aura.agent_case_status NOT NULL DEFAULT 'OPEN',
  owner_user     VARCHAR(80),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_agent_case_doc
ON dwp_aura.agent_case(tenant_id, bukrs, belnr, gjahr, buzei);

CREATE INDEX IF NOT EXISTS ix_agent_case_status
ON dwp_aura.agent_case(tenant_id, status, detected_at DESC);

CREATE TABLE IF NOT EXISTS dwp_aura.agent_action (
  action_id      BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  case_id        BIGINT NOT NULL REFERENCES dwp_aura.agent_case(case_id) ON DELETE CASCADE,
  action_type    VARCHAR(50) NOT NULL,
  action_payload JSONB,
  planned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  executed_at    TIMESTAMPTZ,
  status         dwp_aura.agent_action_status NOT NULL DEFAULT 'PLANNED',
  executed_by    VARCHAR(50) NOT NULL,
  error_message  TEXT
);

CREATE INDEX IF NOT EXISTS ix_agent_action_case
ON dwp_aura.agent_action(tenant_id, case_id);

CREATE TABLE IF NOT EXISTS dwp_aura.integration_outbox (
  outbox_id      BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  target_system  TEXT NOT NULL,
  event_type     TEXT NOT NULL,
  event_key      TEXT NOT NULL,
  payload        JSONB NOT NULL,
  status         TEXT NOT NULL DEFAULT 'PENDING',
  retry_count    INT NOT NULL DEFAULT 0,
  next_retry_at  TIMESTAMPTZ,
  last_error     TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_outbox_idempotent
ON dwp_aura.integration_outbox(tenant_id, target_system, event_type, event_key);

CREATE INDEX IF NOT EXISTS ix_outbox_status
ON dwp_aura.integration_outbox(tenant_id, status, next_retry_at);

-- ======================================================================
-- 7) 정책 문서 메타데이터
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.policy_doc_metadata (
  doc_id        BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  policy_id     TEXT NOT NULL,
  category      TEXT NOT NULL,
  effective_date DATE,
  priority      INT NOT NULL DEFAULT 100,
  title         TEXT,
  content_hash  TEXT,
  source_uri    TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, policy_id)
);

COMMENT ON SCHEMA dwp_aura IS 'DWP Aura Service 전용 스키마 (Self-healing Finance 포함).';
