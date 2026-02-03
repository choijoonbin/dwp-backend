package com.dwp.services.synapsex.dto.case_;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /cases/{caseId}/comment 요청
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseCommentRequest {

    @NotBlank(message = "commentText is required")
    private String commentText;
}
