package com.dwp.services.synapsex.dto.integration;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class IntegrationOutboxEnqueueRequest {

    @NotBlank(message = "targetSystem is required")
    private String targetSystem;

    @NotBlank(message = "eventType is required")
    private String eventType;

    @NotBlank(message = "eventKey is required")
    private String eventKey;

    private Map<String, Object> payload;
}
