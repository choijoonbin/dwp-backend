package com.dwp.services.synapsex.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Enterprise RAG Hybrid Search 응답 DTO
 * Parent(조문) 기준 그룹핑 + Child snippet 포함
 * RRF(Reciprocal Rank Fusion) 알고리즘 적용 결과
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagHybridSearchResponse {

    /** 검색 전략 */
    private String strategy;

    /** 총 결과 수 */
    private Integer totalHits;

    /** 검색 쿼리 해시 (FE 캐시 키 용도) */
    private String queryHash;

    /** Parent(조문) 단위 그룹 결과 */
    private List<ParentGroup> parents;

    /** 검색 메타 정보 */
    private SearchMeta meta;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParentGroup {
        /** Parent 청크 ID (문자열) */
        private String parentId;

        /** 조문 번호 (예: "제11조") */
        private String articleNo;

        /** 조문 제목 */
        private String title;

        /** 조문 전체 텍스트 */
        private String text;

        /** 문서 ID (문자열) */
        private String docId;

        /** 문서 제목 */
        private String docTitle;

        /** 최고 RRF 점수 (children 중 최대값) */
        private Double maxScore;

        /** 하위 청크(항/호) 목록 */
        private List<ChildChunk> children;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChildChunk {
        /** 청크 ID (문자열) */
        private String chunkId;
        private Integer chunkIndex;
        private String nodeType;
        private String snippet;
        private Integer pageNo;

        /** 최종 RRF 점수 */
        private Double score;

        /** 최종 RRF 점수 (FE 호환용, score와 동일) */
        private Double finalScore;

        /** Vector 유사도 순위 */
        private Integer vectorRank;

        /** BM25 순위 */
        private Integer keywordRank;

        /** 항/호 (예: "2항", "제1호") */
        private String clause;

        /** 위치 정보 (예: "규정 제11조 2항") */
        private String location;

        /** FE 원문 이동용 고유 ID */
        private String anchorId;

        /** 계층 경로 (브레드크럼용) */
        private List<HierarchyPathItem> hierarchyPath;

        /** 메타데이터 */
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HierarchyPathItem {
        /** 레벨: CHAPTER, ARTICLE, CLAUSE */
        private String level;
        /** 번호 (예: "11", "2") */
        private String number;
        /** 명칭 (예: "AI 에이전트의 역할") */
        private String title;
        /** anchorId (원문 이동용, chunkId 사용) */
        private String anchorId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchMeta {
        /** 검색 소요 시간 (ms) */
        private Long elapsedMs;

        /** Vector 검색 후보 수 */
        private Integer vectorCandidates;

        /** BM25 검색 후보 수 */
        private Integer keywordCandidates;

        /** RRF 적용 여부 */
        private Boolean rrfApplied;

        /** 저신뢰 결과 포함 여부 */
        private Boolean lowConfidenceIncluded;
    }
}
