package com.dwp.services.platform.home.personalization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface HomeCommandReceiptRepository
        extends JpaRepository<HomeCommandReceipt, UUID> {
    Optional<HomeCommandReceipt> findByTenantIdAndActorIdAndCommandId(
            Long tenantId, Long actorId, UUID commandId);

    long deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
