package com.dwp.services.synapsex.dto.feedback;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackLabelDto {
    private Long feedbackId;
    private String targetType;
    private String targetId;
    private String label;
    private String comment;
    private Long createdBy;
    private Instant createdAt;
}
