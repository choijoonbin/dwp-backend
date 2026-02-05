package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.FiOpenItem;
import com.dwp.services.synapsex.entity.FiOpenItemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FiOpenItemRepository extends JpaRepository<FiOpenItem, FiOpenItemId> {

    /** P1: window 내 신규/변경 오픈아이템 (last_update_ts between) */
    @Query("SELECT f FROM FiOpenItem f WHERE f.tenantId = :tenantId AND f.lastUpdateTs >= :from AND f.lastUpdateTs < :to")
    List<FiOpenItem> findByTenantIdAndLastUpdateTsBetween(@Param("tenantId") Long tenantId,
                                                           @Param("from") Instant from,
                                                           @Param("to") Instant to);

    Optional<FiOpenItem> findByTenantIdAndBukrsAndBelnrAndGjahrAndBuzei(
            Long tenantId, String bukrs, String belnr, String gjahr, String buzei);

    List<FiOpenItem> findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
            Long tenantId, String bukrs, String belnr, String gjahr);

    List<FiOpenItem> findByTenantIdAndLifnr(Long tenantId, String lifnr, org.springframework.data.domain.Pageable pageable);

    List<FiOpenItem> findByTenantIdAndKunnr(Long tenantId, String kunnr, org.springframework.data.domain.Pageable pageable);

    List<FiOpenItem> findByTenantId(Long tenantId);
}
