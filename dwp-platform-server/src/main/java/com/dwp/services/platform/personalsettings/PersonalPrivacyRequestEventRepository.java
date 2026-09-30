package com.dwp.services.platform.personalsettings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface PersonalPrivacyRequestEventRepository
        extends JpaRepository<PersonalPrivacyRequestEvent, UUID> {

    List<PersonalPrivacyRequestEvent> findByRequestIdAndTenantIdAndUserIdOrderByOccurredAtDescIdDesc(
            UUID requestId, Long tenantId, Long userId, Pageable pageable);
}
