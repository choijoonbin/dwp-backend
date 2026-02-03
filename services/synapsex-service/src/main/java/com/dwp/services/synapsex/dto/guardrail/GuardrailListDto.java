package com.dwp.services.synapsex.dto.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardrailListDto {
    private Long guardrailId;
    private String name;
    private String scope;
    private JsonNode ruleJson;
    private Boolean isEnabled;
    private Instant createdAt;
}
