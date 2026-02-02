package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.PolicyPiiField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PolicyPiiFieldRepository extends JpaRepository<PolicyPiiField, Long> {

    List<PolicyPiiField> findByTenantIdAndProfileIdOrderByFieldName(Long tenantId, Long profileId);

    Optional<PolicyPiiField> findByTenantIdAndProfileIdAndFieldName(Long tenantId, Long profileId, String fieldName);

    long countByTenantIdAndProfileId(Long tenantId, Long profileId);
}
