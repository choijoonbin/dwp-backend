package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.ConfigKv;
import com.dwp.services.synapsex.entity.ConfigKvId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigKvRepository extends JpaRepository<ConfigKv, ConfigKvId> {

    List<ConfigKv> findByTenantIdAndProfileId(Long tenantId, Long profileId);

    Optional<ConfigKv> findByTenantIdAndProfileIdAndConfigKey(Long tenantId, Long profileId, String configKey);
}
