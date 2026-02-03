package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.FiOpenItem;
import com.dwp.services.synapsex.entity.FiOpenItemId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FiOpenItemRepository extends JpaRepository<FiOpenItem, FiOpenItemId> {

    Optional<FiOpenItem> findByTenantIdAndBukrsAndBelnrAndGjahrAndBuzei(
            Long tenantId, String bukrs, String belnr, String gjahr, String buzei);

    List<FiOpenItem> findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
            Long tenantId, String bukrs, String belnr, String gjahr);

    List<FiOpenItem> findByTenantIdAndLifnr(Long tenantId, String lifnr, org.springframework.data.domain.Pageable pageable);

    List<FiOpenItem> findByTenantIdAndKunnr(Long tenantId, String kunnr, org.springframework.data.domain.Pageable pageable);

    List<FiOpenItem> findByTenantId(Long tenantId);
}
