package com.dwp.services.synapsex.service.dictionary;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.dto.dictionary.DictionaryTermDto;
import com.dwp.services.synapsex.dto.dictionary.DictionaryTermUpsertRequest;
import com.dwp.services.synapsex.entity.DictionaryTerm;
import com.dwp.services.synapsex.repository.DictionaryTermRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Phase 3 Dictionary 명령 서비스
 */
@Service
@RequiredArgsConstructor
public class DictionaryCommandService {

    private final DictionaryTermRepository dictionaryTermRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public DictionaryTermDto create(Long tenantId, DictionaryTermUpsertRequest request) {
        if (dictionaryTermRepository.findByTenantIdAndTermKey(tenantId, request.getTermKey()).isPresent()) {
            throw new IllegalArgumentException("Term key already exists: " + request.getTermKey());
        }
        DictionaryTerm t = DictionaryTerm.builder()
                .tenantId(tenantId)
                .termKey(request.getTermKey())
                .labelKo(request.getLabelKo())
                .description(request.getDescription())
                .category(request.getCategory())
                .build();
        t = dictionaryTermRepository.save(t);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ADMIN, AuditEventConstants.TYPE_DICTIONARY_CHANGE,
                "DICTIONARY_TERM", String.valueOf(t.getTermId()),
                AuditEventConstants.ACTOR_HUMAN, null, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, Map.of("termKey", t.getTermKey(), "category", t.getCategory() != null ? t.getCategory() : ""), null, null, null,
                null, null, null, null, null);
        return toDto(t);
    }

    @Transactional
    public DictionaryTermDto update(Long tenantId, Long termId, DictionaryTermUpsertRequest request) {
        DictionaryTerm t = dictionaryTermRepository.findById(termId)
                .filter(x -> tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Term not found: " + termId));
        t.setTermKey(request.getTermKey());
        t.setLabelKo(request.getLabelKo());
        t.setDescription(request.getDescription());
        t.setCategory(request.getCategory());
        t = dictionaryTermRepository.save(t);
        return toDto(t);
    }

    @Transactional
    public void delete(Long tenantId, Long termId) {
        DictionaryTerm t = dictionaryTermRepository.findById(termId)
                .filter(x -> tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Term not found: " + termId));
        dictionaryTermRepository.delete(t);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ADMIN, AuditEventConstants.TYPE_DICTIONARY_CHANGE,
                "DICTIONARY_TERM", String.valueOf(termId),
                AuditEventConstants.ACTOR_HUMAN, null, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                Map.of("termKey", t.getTermKey()), null, null, null, null,
                null, null, null, null, null);
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
