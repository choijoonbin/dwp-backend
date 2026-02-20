package com.dwp.services.synapsex.dto.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Enterprise RAG Hybrid Search 요청 DTO
 * RRF(Reciprocal Rank Fusion) 알고리즘 적용: Vector(7) : Keyword(3)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagHybridSearchRequest {

    @NotBlank(message = "검색어는 필수입니다")
    private String query;

    /** 검색 전략: VECTOR_ONLY, HYBRID (기본값: HYBRID) */
    @Builder.Default
    private SearchStrategy strategy = SearchStrategy.HYBRID;

    /** 검색 결과 수 (기본값: 30, 최대: 100) */
    @Builder.Default
    @Min(1) @Max(100)
    private Integer topK = 30;

    /** 최소 유사도 점수 (0~1, 기본값: 0.45) */
    @Builder.Default
    @Min(0) @Max(1)
    private Double minScore = 0.45;

    /** Vector 가중치 (RRF에서 7:3 비율, 기본값: 0.7) */
    @Builder.Default
    @Min(0) @Max(1)
    private Double vectorWeight = 0.7;

    /** Keyword(BM25) 가중치 (RRF에서 7:3 비율, 기본값: 0.3) */
    @Builder.Default
    @Min(0) @Max(1)
    private Double keywordWeight = 0.3;

    /** Parent(조문) 확장 반환 여부 (기본값: true) */
    @Builder.Default
    private Boolean returnParents = true;

    /** 필터 조건 */
    private SearchFilters filters;

    public enum SearchStrategy {
        VECTOR_ONLY, HYBRID
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchFilters {
        /** 문서 ID */
        private String docId;
        /** 도메인 필터 (HR, MANUFACTURING 등) */
        private String domain;
        /** 조항 필터 (예: "제5조") */
        private String article;
        /** 항 필터 (예: "2항") */
        private String clause;
        /** 노드 타입 필터: ARTICLE, CLAUSE, PARAGRAPH */
        private String nodeType;
    }
}
