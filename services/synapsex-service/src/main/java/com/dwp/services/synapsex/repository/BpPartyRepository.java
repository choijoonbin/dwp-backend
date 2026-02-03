package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.BpParty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BpPartyRepository extends JpaRepository<BpParty, Long> {

    Optional<BpParty> findByTenantIdAndPartyTypeAndPartyCode(
            Long tenantId, String partyType, String partyCode);
}
