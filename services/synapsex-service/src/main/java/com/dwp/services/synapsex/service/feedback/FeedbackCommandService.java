package com.dwp.services.synapsex.service.feedback;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.dto.feedback.FeedbackLabelDto;
import com.dwp.services.synapsex.dto.feedback.FeedbackCreateRequest;
import com.dwp.services.synapsex.entity.FeedbackLabel;
import com.dwp.services.synapsex.repository.FeedbackLabelRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Phase 3 Feedback 명령 서비스
 */
@Service
@RequiredArgsConstructor
public class FeedbackCommandService {

    private final FeedbackLabelRepository feedbackLabelRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public FeedbackLabelDto create(Long tenantId, FeedbackCreateRequest request, Long createdBy) {
        FeedbackLabel f = FeedbackLabel.builder()
                .tenantId(tenantId)
                .targetType(request.getTargetType())
                .targetId(request.getTargetId())
                .label(request.getLabel())
                .comment(request.getComment())
                .createdBy(createdBy)
                .build();
        f = feedbackLabelRepository.save(f);

        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_FEEDBACK, AuditEventConstants.TYPE_CREATE,
                "FEEDBACK_LABEL", String.valueOf(f.getFeedbackId()),
                AuditEventConstants.ACTOR_HUMAN, createdBy, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, Map.of("targetType", f.getTargetType(), "targetId", f.getTargetId(), "label", f.getLabel()),
                null, null, null,
                null, null, null, null, null);

        return FeedbackLabelDto.builder()
                .feedbackId(f.getFeedbackId())
                .targetType(f.getTargetType())
                .targetId(f.getTargetId())
                .label(f.getLabel())
                .comment(f.getComment())
                .createdBy(f.getCreatedBy())
                .createdAt(f.getCreatedAt())
                .build();
    }
}
