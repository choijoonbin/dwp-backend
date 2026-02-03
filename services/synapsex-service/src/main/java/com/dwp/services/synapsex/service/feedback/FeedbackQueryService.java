package com.dwp.services.synapsex.service.feedback;

import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.feedback.FeedbackLabelDto;
import com.dwp.services.synapsex.entity.FeedbackLabel;
import com.dwp.services.synapsex.repository.FeedbackLabelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 3 Feedback 조회 서비스
 */
@Service
@RequiredArgsConstructor
public class FeedbackQueryService {

    private final FeedbackLabelRepository feedbackLabelRepository;

    @Transactional(readOnly = true)
    public PageResponse<FeedbackLabelDto> listByTarget(Long tenantId, String targetType, String targetId,
                                                      int page, int size, String sort) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Sort sortObj = parseSort(sort, "createdAt");
        Pageable pageable = PageRequest.of(p, s, sortObj);

        var pageResult = switch (filterMode(targetType, targetId)) {
            case BOTH -> feedbackLabelRepository.findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                    tenantId, targetType, targetId, pageable);
            case TYPE_ONLY -> feedbackLabelRepository.findByTenantIdAndTargetTypeOrderByCreatedAtDesc(
                    tenantId, targetType, pageable);
            default -> feedbackLabelRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
        };

        List<FeedbackLabelDto> items = pageResult.getContent().stream().map(this::toDto).collect(Collectors.toList());
        return PageResponse.of(items, pageResult.getTotalElements(), p, s);
    }

    private FilterMode filterMode(String targetType, String targetId) {
        if (targetType != null && !targetType.isBlank() && targetId != null && !targetId.isBlank()) {
            return FilterMode.BOTH;
        }
        if (targetType != null && !targetType.isBlank()) {
            return FilterMode.TYPE_ONLY;
        }
        return FilterMode.NONE;
    }

    private Sort parseSort(String sort, String defaultField) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, defaultField);
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        boolean asc = parts.length >= 2 && "asc".equalsIgnoreCase(parts[parts.length - 1].trim());
        return Sort.by(asc ? Sort.Direction.ASC : Sort.Direction.DESC, field);
    }

    private enum FilterMode { NONE, TYPE_ONLY, BOTH }

    private FeedbackLabelDto toDto(FeedbackLabel f) {
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
