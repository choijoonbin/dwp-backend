package com.dwp.services.synapsex.service.guardrail;

import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.guardrail.GuardrailListDto;
import com.dwp.services.synapsex.entity.PolicyGuardrail;
import com.dwp.services.synapsex.repository.PolicyGuardrailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Phase 3 Guardrails 조회 서비스
 */
@Service
@RequiredArgsConstructor
public class GuardrailQueryService {

    private final PolicyGuardrailRepository policyGuardrailRepository;

    @Transactional(readOnly = true)
    public PageResponse<GuardrailListDto> listGuardrails(Long tenantId, Boolean enabledOnly, int page, int size, String sort) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Sort sortObj = parseSort(sort, "guardrailId");
        Pageable pageable = PageRequest.of(p, s, sortObj);

        var pageResult = Boolean.TRUE.equals(enabledOnly)
                ? policyGuardrailRepository.findByTenantIdAndIsEnabledTrueOrderByGuardrailIdAsc(tenantId, pageable)
                : policyGuardrailRepository.findByTenantIdOrderByGuardrailIdAsc(tenantId, pageable);

        List<GuardrailListDto> items = pageResult.getContent().stream().map(this::toDto).collect(Collectors.toList());
        return PageResponse.of(items, pageResult.getTotalElements(), p, s);
    }

    private Sort parseSort(String sort, String defaultField) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.ASC, defaultField);
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        boolean asc = parts.length < 2 || !"desc".equalsIgnoreCase(parts[parts.length - 1].trim());
        return Sort.by(asc ? Sort.Direction.ASC : Sort.Direction.DESC, field);
    }

    @Transactional(readOnly = true)
    public Optional<GuardrailListDto> getById(Long tenantId, Long guardrailId) {
        return policyGuardrailRepository.findById(guardrailId)
                .filter(g -> tenantId.equals(g.getTenantId()))
                .map(this::toDto);
    }

    private GuardrailListDto toDto(PolicyGuardrail g) {
        return GuardrailListDto.builder()
                .guardrailId(g.getGuardrailId())
                .name(g.getName())
                .scope(g.getScope())
                .ruleJson(g.getRuleJson())
                .isEnabled(g.getIsEnabled())
                .createdAt(g.getCreatedAt())
                .build();
    }
}
