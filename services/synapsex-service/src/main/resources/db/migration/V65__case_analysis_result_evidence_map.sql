-- V65: 사실-규정 매핑 (Side-by-Side). 위반 의심 전표(fi_doc_item) ↔ 근거 규정 청크(rag_chunk) 1:1
SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.case_analysis_result
  ADD COLUMN IF NOT EXISTS evidence_map_json jsonb;

COMMENT ON COLUMN dwp_aura.case_analysis_result.evidence_map_json IS 'evidence[i] ↔ ragRefs[i] 1:1 매핑. [{ docId, itemId, chunkId }, ...]';
