package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.ReconResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconResultRepository extends JpaRepository<ReconResult, Long> {

    List<ReconResult> findByRunIdOrderByResultIdAsc(Long runId);

    /** ICC 대시보드: tenant + FAIL 건수 */
    long countByTenantIdAndStatus(Long tenantId, String status);

    /** ICC 대시보드: tenant + FAIL 최신 5건 */
    List<ReconResult> findByTenantIdAndStatusOrderByResultIdDesc(Long tenantId, String status, Pageable pageable);

    /** Phase 6 HITL 완료: 케이스 전표(bukrs-belnr-gjahr)에 해당하는 recon 결과 조회 */
    List<ReconResult> findByTenantIdAndResourceKeyStartingWith(Long tenantId, String resourceKeyPrefix);
}
