package com.dwp.services.synapsex.service.detect;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.detect.DetectRunDto;
import com.dwp.services.synapsex.entity.DetectRun;
import com.dwp.services.synapsex.repository.DetectRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Detect Run 관제 조회 전용.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DetectRunQueryService {

    private final DetectRunRepository detectRunRepository;

    public Page<DetectRunDto> search(Long tenantId, Instant from, Instant to, String status, Pageable pageable) {
        Specification<DetectRun> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("startedAt"), to));
            if (status != null && !status.isBlank()) predicates.add(cb.equal(root.get("status"), status));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return detectRunRepository.findAll(spec, pageable).map(DetectRunDto::from);
    }

    public DetectRunDto getById(Long tenantId, Long runId) {
        DetectRun run = detectRunRepository.findById(runId)
                .filter(r -> r.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "Detect Run을 찾을 수 없습니다."));
        return DetectRunDto.from(run);
    }
}
