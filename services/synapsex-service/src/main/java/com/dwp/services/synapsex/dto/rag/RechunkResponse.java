package com.dwp.services.synapsex.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 재청킹 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RechunkResponse {

    private Long docId;

    /** 상태: PROCESSING, COMPLETED, FAILED */
    private String status;

    /** 청크 수 (완료 시) */
    private Integer chunkCount;

    /** 메시지 */
    private String message;
}
