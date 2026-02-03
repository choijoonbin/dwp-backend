package com.dwp.services.synapsex.dto.action;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * C2) POST /actions 요청
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateActionRequest {

    @NotNull(message = "caseId is required")
    private Long caseId;

    @NotNull(message = "actionType is required")
    private String actionType;  // PAYMENT_BLOCK, REQUEST_INFO, DISMISS, RELEASE_BLOCK, etc

    private JsonNode payload;
}
