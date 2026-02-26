package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.BpParty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BpPartyRepository extends JpaRepository<BpParty, Long> {

    Optional<BpParty> findByTenantIdAndPartyTypeAndPartyCode(
            Long tenantId, String partyType, String partyCode);

    /** party_code로 조회 (V001, C001 등). VENDOR/CUSTOMER 중 첫 매칭 반환 */
    Optional<BpParty> findFirstByTenantIdAndPartyCode(Long tenantId, String partyCode);

    List<BpParty> findByTenantIdAndPartyTypeInOrderByPartyCodeAsc(Long tenantId, Iterable<String> partyTypes);
}
