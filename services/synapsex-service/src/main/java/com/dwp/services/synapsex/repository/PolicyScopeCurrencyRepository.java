package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.PolicyScopeCurrency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PolicyScopeCurrencyRepository extends JpaRepository<PolicyScopeCurrency, Long> {

    List<PolicyScopeCurrency> findByTenantIdAndProfileId(Long tenantId, Long profileId);

    Optional<PolicyScopeCurrency> findByTenantIdAndProfileIdAndCurrencyCode(Long tenantId, Long profileId, String currencyCode);

    List<PolicyScopeCurrency> findByTenantIdAndProfileIdAndIncludedTrue(Long tenantId, Long profileId);

    @Query("SELECT MAX(p.updatedAt) FROM PolicyScopeCurrency p WHERE p.tenantId = :tenantId AND p.profileId = :profileId")
    Optional<Instant> findMaxUpdatedAtByTenantIdAndProfileId(@Param("tenantId") Long tenantId, @Param("profileId") Long profileId);
}
