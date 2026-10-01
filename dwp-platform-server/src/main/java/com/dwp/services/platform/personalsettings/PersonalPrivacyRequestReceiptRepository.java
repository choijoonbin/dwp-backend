package com.dwp.services.platform.personalsettings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PersonalPrivacyRequestReceiptRepository
        extends JpaRepository<PersonalPrivacyRequestReceipt, UUID> {

    List<PersonalPrivacyRequestReceipt> findByTenantIdAndUserIdAndRequestIdInOrderByIssuedAtDescReceiptIdDesc(
            Long tenantId, Long userId, Collection<UUID> requestIds);
}
