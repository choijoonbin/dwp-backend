package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.FiDocHeader;
import com.dwp.services.synapsex.entity.FiDocHeaderId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FiDocHeaderRepository extends JpaRepository<FiDocHeader, FiDocHeaderId> {

    Optional<FiDocHeader> findByTenantIdAndBukrsAndBelnrAndGjahr(
            Long tenantId, String bukrs, String belnr, String gjahr);

    boolean existsByTenantIdAndBukrsAndBelnrAndGjahr(
            Long tenantId, String bukrs, String belnr, String gjahr);

    /** Docs that reverse this doc (reversal_belnr == belnr) */
    java.util.List<FiDocHeader> findByTenantIdAndBukrsAndReversalBelnrAndGjahr(
            Long tenantId, String bukrs, String belnr, String gjahr);
}
