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

    private Long tenantId;
    private Long docId;
    /** 문서 성격: REGULATION, MANUAL, POLICY 등 — Aura 메타데이터 인덱싱용 */
    private String docType;
    private String title;
    private String s3Key;
    private String url;
    private String sourceType;
    /** 로컬 파일 절대 경로. Aura 벡터화 시 문서 읽기용 */
    @JsonProperty("document_path")
    private String documentPath;
}
