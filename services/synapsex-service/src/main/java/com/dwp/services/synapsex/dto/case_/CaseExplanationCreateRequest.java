package com.dwp.services.synapsex.dto.case_;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseExplanationCreateRequest {

    @NotBlank(message = "explanationText is required")
    private String explanationText;

    private String evidenceAttachmentId;
}
