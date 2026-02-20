-- Agent-Document 매핑 테이블 생성 및 knowledge_base_master 제거
-- RAG 라이브러리 통합: 모든 에이전트 지식은 rag_document 테이블을 참조

SET search_path TO dwp_aura, public;

-- 1) agent_document_mapping 테이블 생성
CREATE TABLE IF NOT EXISTS dwp_aura.agent_document_mapping (
    agent_id BIGINT NOT NULL,
    doc_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, doc_id),
    CONSTRAINT fk_agent_document_mapping_agent FOREIGN KEY (agent_id) 
        REFERENCES dwp_aura.agent_master(agent_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_document_mapping_document FOREIGN KEY (doc_id) 
        REFERENCES dwp_aura.rag_document(doc_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_agent_document_mapping_agent_id ON dwp_aura.agent_document_mapping(agent_id);
CREATE INDEX IF NOT EXISTS ix_agent_document_mapping_doc_id ON dwp_aura.agent_document_mapping(doc_id);
CREATE INDEX IF NOT EXISTS ix_agent_document_mapping_tenant_id ON dwp_aura.agent_document_mapping(tenant_id);

COMMENT ON TABLE dwp_aura.agent_document_mapping IS '에이전트-문서 매핑: 에이전트가 사용하는 RAG 문서 목록';
COMMENT ON COLUMN dwp_aura.agent_document_mapping.agent_id IS '에이전트 ID (FK: agent_master)';
COMMENT ON COLUMN dwp_aura.agent_document_mapping.doc_id IS '문서 ID (FK: rag_document)';
COMMENT ON COLUMN dwp_aura.agent_document_mapping.tenant_id IS '테넌트 ID (멀티테넌시 격리)';

-- 2) knowledge_base_master 테이블 삭제 (기존 설계 철회)
-- 주의: 기존 데이터가 있다면 먼저 마이그레이션 필요
DROP TABLE IF EXISTS dwp_aura.knowledge_base_master CASCADE;
