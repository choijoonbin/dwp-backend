package com.dwp.services.synapsex.dto.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Aura 벡터화 트리거 응답 (202 Accepted).
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuraRagVectorizeResponse {

    private String status;  // ACCEPTED
    private String jobId;
    private String message;
}
