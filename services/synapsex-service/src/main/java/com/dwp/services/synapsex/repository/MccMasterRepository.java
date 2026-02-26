package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.MccMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MccMasterRepository extends JpaRepository<MccMaster, Long> {

    List<MccMaster> findByTenantId(Long tenantId);

    List<MccMaster> findByTenantIdAndMccCodeIn(Long tenantId, Collection<String> mccCodes);

    Optional<MccMaster> findFirstByTenantIdAndMccCode(Long tenantId, String mccCode);
}
