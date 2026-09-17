package com.dwp.services.platform.home.runtime;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface HomeOwnerActionReceiptRepository
        extends JpaRepository<HomeOwnerActionReceipt, UUID> {
    Optional<HomeOwnerActionReceipt> findByTenantIdAndUserIdAndCommandId(
            Long tenantId, Long userId, UUID commandId);
}
