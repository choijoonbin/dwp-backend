package com.dwp.services.synapsex.dto.rag;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;
import java.util.List;

/**
 * Aura → Synapse RAG 청크 저장/상태 콜백 요청.
 * POST /api/synapse/rag/status
 * Aura 형식: rag_document_id(string), chunks, batch_index, total_batches 수용.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RagStatusCallbackRequest {

    /** 문서 ID. Aura: doc_id 또는 rag_document_id(string) */
    @JsonAlias("doc_id")
    private Long docId;

    /** Aura가 전송 시 사용. 문자열인 경우 파싱해 docId로 사용 */
    @JsonAlias("rag_document_id")
    private String ragDocumentId;

    /** COMPLETED, FAILED, PROCESSING 등 */
    private String status;

    /** 오류 시 메시지 (선택) */
    private String message;

    /** Aura vectorize/run 식별자 (선택) */
    @JsonAlias({"run_id", "vectorize_run_id"})
    private UUID runId;

    /** Aura quality report payload (선택) */
    @JsonProperty("quality_report")
    private com.fasterxml.jackson.databind.JsonNode qualityReport;

    /** Aura top-level quality gate 결과 (선택). quality_report 미포함 시 fallback으로 사용 */
    @JsonAlias({"quality_gate_passed", "qualityGatePassed"})
    private Boolean qualityGatePassed;

    /** 청크 배열. Aura가 batch_index/total_batches와 함께 전송 */
    private List<@Valid AuraChunkItemDto> chunks;
}
