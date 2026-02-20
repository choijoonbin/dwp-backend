package com.dwp.services.synapsex.dto.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * Aura 벡터화 트리거 요청 (POST /aura/rag/documents/{docId}/vectorize).
 * 파일명 대신 doc_id + doc_type 전달하여 메타데이터 기반 인덱싱.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuraRagVectorizeRequest {

    @JsonProperty("tenant_id")
    private Long tenantId;
    
    @JsonProperty("doc_id")
    private Long docId;
    
    /** Aura 호환용: rag_document_id (string) */
    @JsonProperty("rag_document_id")
    private String ragDocumentId;
    
    /** 문서 성격: HIERARCHICAL, SEQUENTIAL, POLICY 등 — Aura 메타데이터 인덱싱용 */
    @JsonProperty("doc_type")
    private String docType;
    
    private String title;
    private String s3Key;
    private String url;
    private String sourceType;
    
    /** 로컬 파일 절대 경로. Aura 벡터화 시 문서 읽기용 */
    @JsonProperty("document_path")
    private String documentPath;
    
    /** 파일 경로 (재청킹용) */
    @JsonProperty("file_path")
    private String filePath;
    
    /** 콜백 URL */
    @JsonProperty("callback_url")
    private String callbackUrl;
    
    /** 청크 크기 (토큰 수) */
    @JsonProperty("chunk_size")
    private Integer chunkSize;
    
    /** 청크 오버랩 (토큰 수) */
    @JsonProperty("chunk_overlap")
    private Integer chunkOverlap;
}
