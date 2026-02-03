package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.FiDocItem;
import com.dwp.services.synapsex.entity.FiDocItemId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FiDocItemRepository extends JpaRepository<FiDocItem, FiDocItemId> {

    List<FiDocItem> findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
            Long tenantId, String bukrs, String belnr, String gjahr);

    List<FiDocItem> findByTenantIdAndLifnr(Long tenantId, String lifnr, org.springframework.data.domain.Pageable pageable);

    List<FiDocItem> findByTenantIdAndKunnr(Long tenantId, String kunnr, org.springframework.data.domain.Pageable pageable);

    List<FiDocItem> findByTenantId(Long tenantId);
}
