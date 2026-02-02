package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.ConfigProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigProfileRepository extends JpaRepository<ConfigProfile, Long> {

    List<ConfigProfile> findByTenantIdOrderByProfileIdAsc(Long tenantId);

    Optional<ConfigProfile> findByTenantIdAndProfileId(Long tenantId, Long profileId);

    boolean existsByTenantIdAndProfileName(Long tenantId, String profileName);

    Optional<ConfigProfile> findByTenantIdAndIsDefaultTrue(Long tenantId);
}
