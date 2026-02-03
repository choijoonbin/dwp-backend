package com.dwp.services.synapsex.service.audit;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.audit.AuditEventDetailDto;
import com.dwp.services.synapsex.dto.audit.AuditEventDto;
import com.dwp.services.synapsex.dto.audit.AuditEventPageDto;
import com.dwp.services.synapsex.entity.AuditEventLog;
import com.dwp.services.synapsex.repository.AuditEventLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dwp.services.synapsex.util.DrillDownParamUtil;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditEventQueryService {

    private final AuditEventLogRepository repository;

    public AuditEventPageDto search(Long tenantId, Instant from, Instant to,
                                   String category, String type, String outcome, String severity,
                                   Long actorUserId, String actorType,
                                   String resourceType, String resourceId, String q, Pageable pageable) {
        Specification<AuditEventLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            if (category != null && !category.isBlank()) {
                List<String> cats = DrillDownParamUtil.parseMulti(category);
                if (cats.size() == 1) predicates.add(cb.equal(root.get("eventCategory"), cats.get(0)));
                else if (!cats.isEmpty()) predicates.add(root.get("eventCategory").in(cats));
            }
            if (type != null && !type.isBlank()) {
                List<String> types = DrillDownParamUtil.parseMulti(type);
                if (types.size() == 1) predicates.add(cb.equal(root.get("eventType"), types.get(0)));
                else if (!types.isEmpty()) predicates.add(root.get("eventType").in(types));
            }
            if (outcome != null && !outcome.isBlank()) predicates.add(cb.equal(root.get("outcome"), outcome));
            if (severity != null && !severity.isBlank()) predicates.add(cb.equal(root.get("severity"), severity));
            if (actorType != null && !actorType.isBlank()) predicates.add(cb.equal(root.get("actorType"), actorType));
            if (actorUserId != null) predicates.add(cb.equal(root.get("actorUserId"), actorUserId));
            if (resourceType != null && !resourceType.isBlank()) predicates.add(cb.equal(root.get("resourceType"), resourceType));
            if (resourceId != null && !resourceId.isBlank()) predicates.add(cb.equal(root.get("resourceId"), resourceId));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<AuditEventLog> page = repository.findAll(spec, pageable);
        List<AuditEventDto> items = page.getContent().stream().map(this::toDto).collect(Collectors.toList());
        return AuditEventPageDto.builder()
                .items(items)
                .total(page.getTotalElements())
                .pageInfo(AuditEventPageDto.PageInfo.builder()
                        .page(page.getNumber())
                        .size(page.getSize())
                        .totalPages(page.getTotalPages())
                        .total(page.getTotalElements())
                        .build())
                .build();
    }

    public AuditEventDetailDto getById(Long tenantId, Long auditId) {
        AuditEventLog e = repository.findById(auditId)
                .filter(x -> x.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "감사 이벤트를 찾을 수 없습니다."));
        return toDetailDto(e);
    }

    private AuditEventDto toDto(AuditEventLog e) {
        return AuditEventDto.builder()
                .auditId(e.getAuditId())
                .createdAt(e.getCreatedAt())
                .eventCategory(e.getEventCategory())
                .eventType(e.getEventType())
                .resourceType(e.getResourceType())
                .resourceId(e.getResourceId())
                .actorType(e.getActorType())
                .actorUserId(e.getActorUserId())
                .actorDisplayName(e.getActorDisplayName())
                .outcome(e.getOutcome())
                .severity(e.getSeverity())
                .evidenceJson(e.getEvidenceJson())
                .build();
    }

    private AuditEventDetailDto toDetailDto(AuditEventLog e) {
        return AuditEventDetailDto.builder()
                .auditId(e.getAuditId())
                .tenantId(e.getTenantId())
                .createdAt(e.getCreatedAt())
                .eventCategory(e.getEventCategory())
                .eventType(e.getEventType())
                .resourceType(e.getResourceType())
                .resourceId(e.getResourceId())
                .actorType(e.getActorType())
                .actorUserId(e.getActorUserId())
                .actorAgentId(e.getActorAgentId())
                .actorDisplayName(e.getActorDisplayName())
                .channel(e.getChannel())
                .outcome(e.getOutcome())
                .severity(e.getSeverity())
                .beforeJson(e.getBeforeJson())
                .afterJson(e.getAfterJson())
                .diffJson(e.getDiffJson())
                .evidenceJson(e.getEvidenceJson())
                .tags(e.getTags())
                .ipAddress(e.getIpAddress())
                .userAgent(e.getUserAgent())
                .gatewayRequestId(e.getGatewayRequestId())
                .traceId(e.getTraceId())
                .spanId(e.getSpanId())
                .build();
    }
}
