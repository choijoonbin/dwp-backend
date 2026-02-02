package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.MdCompanyCode;
import com.dwp.services.synapsex.entity.MdCompanyCodeId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MdCompanyCodeRepository extends JpaRepository<MdCompanyCode, MdCompanyCodeId> {

    List<MdCompanyCode> findByTenantIdAndIsActiveTrueOrderByBukrsAsc(Long tenantId);

    Optional<MdCompanyCode> findByTenantIdAndBukrs(Long tenantId, String bukrs);

    boolean existsByTenantIdAndBukrs(Long tenantId, String bukrs);
}
