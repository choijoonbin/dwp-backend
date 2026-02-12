package com.dwp.services.synapsex.dto.rag;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Aura 벡터화 결과 청크 1건 (Aura → BE 콜백/응답).
 * BE가 rag_chunk에 저장할 때 사용.
 * Aura 형식: chunk_index, content, embedding, metadata(내부 page_number) 수용.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuraChunkItemDto {

    /** 문서 내 순서 (0-based). Aura: chunk_index */
    @JsonAlias("chunk_index")
    private Integer chunkIndex;

    /** 분할된 텍스트. Aura: content / text / chunk_content → DB chunk_text */
    @JsonAlias({"chunk_content", "content", "text"})
    private String chunkContent;

    /** OpenAI embedding 1536차원. Aura: embedding (number[]) */
    private float[] embedding;

    /** 메타데이터. Aura: metadata (page_number 등) → DB metadata_json, page_no 추출 */
    @JsonAlias({"metadata_json", "metadata"})
    private Map<String, Object> metadataJson;
}
