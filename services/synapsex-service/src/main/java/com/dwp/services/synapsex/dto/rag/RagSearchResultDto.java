package com.dwp.services.synapsex.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchResultDto {
    private Long chunkId;
    private Long docId;
    private String docTitle;
    private Integer pageNo;
    private String chunkText;
    private Double score;  // optional, for embedding similarity
}
