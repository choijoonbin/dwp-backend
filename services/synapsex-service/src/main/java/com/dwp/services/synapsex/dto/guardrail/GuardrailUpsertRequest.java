package com.dwp.services.synapsex.dto.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardrailUpsertRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "scope is required")
    private String scope;  // case_type, action_type, etc.

    @NotNull(message = "ruleJson is required")
    private JsonNode ruleJson;

    private Boolean isEnabled = true;
}
