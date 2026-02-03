package com.dwp.services.synapsex.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentDetailDto {
    private Long docId;
    private String title;
    private String sourceType;
    private String s3Key;
    private String url;
    private String checksum;
    private String status;
    private Instant createdAt;
    private List<RagChunkDto> chunks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RagChunkDto {
        private Long chunkId;
        private Integer pageNo;
        private String chunkText;
        private String embeddingId;
    }
}
