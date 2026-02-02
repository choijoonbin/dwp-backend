package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.PiiPolicyDto;
import com.dwp.services.synapsex.repository.PolicyPiiFieldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PiiPolicyQueryService {

    private final PolicyPiiFieldRepository policyPiiFieldRepository;

    public List<PiiPolicyDto> listByProfile(Long tenantId, Long profileId) {
        return policyPiiFieldRepository.findByTenantIdAndProfileIdOrderByFieldName(tenantId, profileId).stream()
                .map(PiiPolicyDto::from)
                .collect(Collectors.toList());
    }
}
