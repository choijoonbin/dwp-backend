package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.FiDocHeader;
import com.dwp.services.synapsex.entity.FiDocHeaderId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FiDocHeaderRepository extends JpaRepository<FiDocHeader, FiDocHeaderId> {

    /** Phase B: window 내 신규/변경 전표 */
    @Query("SELECT f FROM FiDocHeader f WHERE f.tenantId = :tenantId AND f.createdAt >= :from AND f.createdAt < :to")
    List<FiDocHeader> findByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId,
                                                         @Param("from") Instant from,
                                                         @Param("to") Instant to);

    Optional<FiDocHeader> findByTenantIdAndBukrsAndBelnrAndGjahr(
            Long tenantId, String bukrs, String belnr, String gjahr);

    boolean existsByTenantIdAndBukrsAndBelnrAndGjahr(
            Long tenantId, String bukrs, String belnr, String gjahr);

    /** Docs that reverse this doc (reversal_belnr == belnr) */
    java.util.List<FiDocHeader> findByTenantIdAndBukrsAndReversalBelnrAndGjahr(
            Long tenantId, String bukrs, String belnr, String gjahr);
}
