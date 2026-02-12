package com.dwp.services.synapsex.dto.rag;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aura RAG 청크 콜백 요청.
 * POST /api/synapse/rag/chunks — Aura는 snake_case로 전송하므로 @JsonAlias로 수용.
 * rag_document_id는 문자열 또는 숫자(10 등) 모두 수용.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RagChunksCallbackRequest {

    @NotBlank(message = "rag_document_id는 필수입니다.")
    private String ragDocumentId;

    /** Aura가 "rag_document_id"를 문자열 또는 숫자(10)로 보내도 수용 */
    @JsonSetter("rag_document_id")
    public void setRagDocumentIdFromObject(Object value) {
        this.ragDocumentId = value == null ? null : String.valueOf(value).strip();
    }

    /** Aura가 보내는 청크 배열. 중첩 검증 생략해 Aura 필드 형식 차이로 400 방지 */
    private List<AuraChunkItemDto> chunks;

    @JsonAlias("batch_index")
    private Integer batchIndex;

    @JsonAlias("total_batches")
    private Integer totalBatches;
}
