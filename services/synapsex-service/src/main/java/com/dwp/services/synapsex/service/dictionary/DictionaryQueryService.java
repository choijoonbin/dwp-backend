package com.dwp.services.synapsex.service.dictionary;

import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.dictionary.DictionaryTermDto;
import com.dwp.services.synapsex.entity.DictionaryTerm;
import com.dwp.services.synapsex.repository.DictionaryTermRepository;
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
 * Phase 3 Dictionary 조회 서비스
 */
@Service
@RequiredArgsConstructor
public class DictionaryQueryService {

    private final DictionaryTermRepository dictionaryTermRepository;

    @Transactional(readOnly = true)
    public PageResponse<DictionaryTermDto> listTerms(Long tenantId, String category, int page, int size, String sort) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Sort sortObj = parseSort(sort, "termKey");
        Pageable pageable = PageRequest.of(p, s, sortObj);

        var pageResult = category != null && !category.isBlank()
                ? dictionaryTermRepository.findByTenantIdAndCategoryOrderByTermKeyAsc(tenantId, category, pageable)
                : dictionaryTermRepository.findByTenantIdOrderByTermKeyAsc(tenantId, pageable);

        List<DictionaryTermDto> items = pageResult.getContent().stream().map(this::toDto).collect(Collectors.toList());
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
    public Optional<DictionaryTermDto> getByTermKey(Long tenantId, String termKey) {
        return dictionaryTermRepository.findByTenantIdAndTermKey(tenantId, termKey).map(this::toDto);
    }

    private DictionaryTermDto toDto(DictionaryTerm t) {
        return DictionaryTermDto.builder()
                .termId(t.getTermId())
                .termKey(t.getTermKey())
                .labelKo(t.getLabelKo())
                .description(t.getDescription())
                .category(t.getCategory())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
