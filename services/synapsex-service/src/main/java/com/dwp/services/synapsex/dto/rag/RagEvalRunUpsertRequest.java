package com.dwp.services.synapsex.dto.rag;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalRunUpsertRequest {

    @NotBlank
    private String runKey;

    @NotNull
    private BigDecimal zeroRate;

    @NotNull
    private BigDecimal hitAtK;

    @NotNull
    private BigDecimal strictHitTop1;

    @NotNull
    private Integer totalCases;

    @NotNull
    private JsonNode resultJson;

    /** 생략 시 BE 기준(zero_rate<=0.20, hit_at_k>=0.70)으로 계산 */
    private Boolean gatePassed;
}
