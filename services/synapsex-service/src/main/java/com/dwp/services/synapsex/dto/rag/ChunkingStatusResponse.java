package com.dwp.services.synapsex.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 청킹 상태 조회 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkingStatusResponse {

    private Long docId;

    /** 상태: PROCESSING, VECTORIZING, COMPLETED, FAILED, READY */
    private String status;

    /** 현재 청크 수 */
    private Integer chunkCount;

    /** 청킹 전략 */
    private String strategy;

    /** 문서 유형 */
    private String docType;
}
