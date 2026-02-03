package com.dwp.services.synapsex.dto.case_;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A3) POST /cases/{caseId}/status 요청
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseStatusUpdateRequest {

    @NotBlank(message = "status is required")
    private String status;  // TRIAGED, IN_PROGRESS, RESOLVED, DISMISSED
}
