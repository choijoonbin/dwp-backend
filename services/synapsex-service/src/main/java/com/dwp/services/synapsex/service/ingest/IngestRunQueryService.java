package com.dwp.services.synapsex.service.ingest;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.ingest.IngestRunDto;
import com.dwp.services.synapsex.entity.IngestRun;
import com.dwp.services.synapsex.repository.IngestRunRepository;
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
 * Ingest Run 관제 조회 전용.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IngestRunQueryService {

    private final IngestRunRepository ingestRunRepository;

    public Page<IngestRunDto> search(Long tenantId, Instant from, Instant to, String status, Pageable pageable) {
        Specification<IngestRun> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("startedAt"), to));
            if (status != null && !status.isBlank()) predicates.add(cb.equal(root.get("status"), status));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return ingestRunRepository.findAll(spec, pageable).map(IngestRunDto::from);
    }

    public IngestRunDto getById(Long tenantId, Long runId) {
        IngestRun run = ingestRunRepository.findById(runId)
                .filter(r -> r.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "Ingest Run을 찾을 수 없습니다."));
        return IngestRunDto.from(run);
    }
}
