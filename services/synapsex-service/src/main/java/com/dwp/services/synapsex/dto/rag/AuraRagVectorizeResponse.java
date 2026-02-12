package com.dwp.services.synapsex.dto.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aura 벡터화 트리거 응답 (202 Accepted).
 * Feign/Jackson 역직렬화를 위해 no-args 생성자 필요.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuraRagVectorizeResponse {

    private String status;  // ACCEPTED
    private String jobId;
    private String message;
}
