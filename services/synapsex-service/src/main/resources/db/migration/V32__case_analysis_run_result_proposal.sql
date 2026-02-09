-- V32: Phase2 Case Analysis Run, Result, Action Proposal
-- 목적: Aura Agentic AI 분석→권고 흐름 저장. docs/job/back.txt
-- 참고: CaseAnalysisRun, CaseAnalysisResult, CaseActionProposal

SET search_path TO dwp_aura, public;

-- 1. case_analysis_run — 케이스 분석 실행 단위
CREATE TABLE IF NOT EXISTS dwp_aura.case_analysis_run (
  run_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        BIGINT NOT NULL,
  case_id          BIGINT NOT NULL,
  status           VARCHAR(30) NOT NULL DEFAULT 'STARTED',
  mode             VARCHAR(20) NOT NULL DEFAULT 'LIVE',
  requested_by     VARCHAR(20) NOT NULL DEFAULT 'HUMAN',
  started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at      TIMESTAMPTZ,
  error_message    TEXT,
  aura_trace_id    VARCHAR(100),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_case_analysis_run_tenant_case
ON dwp_aura.case_analysis_run(tenant_id, case_id);
CREATE INDEX IF NOT EXISTS ix_case_analysis_run_status
ON dwp_aura.case_analysis_run(tenant_id, status);

COMMENT ON TABLE dwp_aura.case_analysis_run IS 'Phase2: 케이스 분석 실행 단위 (Aura 연동)';
COMMENT ON COLUMN dwp_aura.case_analysis_run.status IS 'STARTED | RUNNING | COMPLETED | FAILED';
COMMENT ON COLUMN dwp_aura.case_analysis_run.mode IS 'LIVE | SIMULATION';
COMMENT ON COLUMN dwp_aura.case_analysis_run.requested_by IS 'HUMAN | SYSTEM';

-- 2. case_analysis_result — 분석 결과 (run당 1건)
CREATE TABLE IF NOT EXISTS dwp_aura.case_analysis_result (
  run_id             UUID PRIMARY KEY REFERENCES dwp_aura.case_analysis_run(run_id) ON DELETE CASCADE,
  score              DECIMAL(5,2),
  severity           VARCHAR(20),
  reason_text        TEXT,
  confidence_json    JSONB,
  evidence_json      JSONB,
  similar_json       JSONB,
  rag_refs_json      JSONB,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE dwp_aura.case_analysis_result IS 'Phase2: 분석 결과 (점수/근거/유사/RAG/권고)';

-- 3. case_action_proposal — AI 권고 조치
CREATE TABLE IF NOT EXISTS dwp_aura.case_action_proposal (
  proposal_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          BIGINT NOT NULL,
  case_id            BIGINT NOT NULL,
  run_id             UUID REFERENCES dwp_aura.case_analysis_run(run_id) ON DELETE SET NULL,
  type               VARCHAR(50) NOT NULL,
  status             VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  risk_level         VARCHAR(20),
  rationale          TEXT,
  payload_json       JSONB,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_case_action_proposal_tenant_case
ON dwp_aura.case_action_proposal(tenant_id, case_id);
CREATE INDEX IF NOT EXISTS ix_case_action_proposal_run
ON dwp_aura.case_action_proposal(run_id);

COMMENT ON TABLE dwp_aura.case_action_proposal IS 'Phase2: AI 권고 조치 (승인/거절/실행)';
COMMENT ON COLUMN dwp_aura.case_action_proposal.status IS 'DRAFT | PROPOSED | APPROVED | REJECTED | EXECUTED | FAILED';
