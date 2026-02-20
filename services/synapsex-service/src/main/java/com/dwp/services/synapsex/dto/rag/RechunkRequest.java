package com.dwp.services.synapsex.dto.rag;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 재청킹 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RechunkRequest {

    /** 청킹 전략: HIERARCHICAL, SEQUENTIAL, SEMANTIC 등 */
    @NotBlank(message = "strategy는 필수입니다")
    private String strategy;

    /** 청크 크기 (토큰 수, 기본값: 512) */
    private Integer chunkSize;

    /** 청크 오버랩 (토큰 수, 기본값: 50) */
    private Integer chunkOverlap;
}
