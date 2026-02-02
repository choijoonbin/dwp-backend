package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.ThresholdDto;
import com.dwp.services.synapsex.entity.RuleThreshold;
import com.dwp.services.synapsex.repository.RuleThresholdRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ThresholdQueryService {

    private final RuleThresholdRepository ruleThresholdRepository;

    public Page<ThresholdDto> search(Long tenantId, Long profileId, String dimension, String waers, String q, Pageable pageable) {
        Page<RuleThreshold> page;
        if (profileId != null) {
            page = ruleThresholdRepository.findByTenantIdAndProfileId(tenantId, profileId, pageable);
        } else if (waers != null && !waers.isBlank()) {
            page = ruleThresholdRepository.findByTenantIdAndWaers(tenantId, waers, pageable);
        } else {
            page = ruleThresholdRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(ThresholdDto::from);
    }

    public ThresholdDto getById(Long tenantId, Long thresholdId) {
        return ruleThresholdRepository.findById(thresholdId)
                .filter(e -> e.getTenantId().equals(tenantId))
                .map(ThresholdDto::from)
                .orElse(null);
    }
}
