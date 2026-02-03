package com.dwp.services.synapsex.dto.feedback;

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
public class FeedbackCreateRequest {

    @NotBlank(message = "targetType is required")
    private String targetType;  // CASE, DOC, ENTITY

    @NotBlank(message = "targetId is required")
    private String targetId;

    @NotBlank(message = "label is required")
    private String label;  // VALID, INVALID, NEEDS_REVIEW

    private String comment;
}
