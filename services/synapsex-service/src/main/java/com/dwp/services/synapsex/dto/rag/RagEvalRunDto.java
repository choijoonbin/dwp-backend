package com.dwp.services.synapsex.dto.rag;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalRunDto {

    private Long id;
    private String runKey;
    private BigDecimal zeroRate;
    private BigDecimal hitAtK;
    private BigDecimal strictHitTop1;
    private Integer totalCases;
    private JsonNode resultJson;
    private Boolean gatePassed;
    private Instant createdAt;
}
