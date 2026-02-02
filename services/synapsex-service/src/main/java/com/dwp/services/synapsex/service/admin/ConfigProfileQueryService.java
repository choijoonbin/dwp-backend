package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.ConfigProfileDto;
import com.dwp.services.synapsex.entity.ConfigProfile;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfigProfileQueryService {

    private final ConfigProfileRepository configProfileRepository;

    public List<ConfigProfileDto> listByTenant(Long tenantId) {
        return configProfileRepository.findByTenantIdOrderByProfileIdAsc(tenantId).stream()
                .map(ConfigProfileDto::from)
                .collect(Collectors.toList());
    }

    public ConfigProfileDto getByTenantAndId(Long tenantId, Long profileId) {
        return configProfileRepository.findByTenantIdAndProfileId(tenantId, profileId)
                .map(ConfigProfileDto::from)
                .orElse(null);
    }

    public ConfigProfile getEntity(Long tenantId, Long profileId) {
        return configProfileRepository.findByTenantIdAndProfileId(tenantId, profileId).orElse(null);
    }
}
