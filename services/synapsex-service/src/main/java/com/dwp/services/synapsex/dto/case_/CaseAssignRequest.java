package com.dwp.services.synapsex.dto.case_;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /cases/{caseId}/assign 요청
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseAssignRequest {

    @NotNull(message = "assigneeUserId is required")
    private Long assigneeUserId;
}
