package com.dwp.services.platform.personalsettings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonalPrivacyRequestRepository extends JpaRepository<PersonalPrivacyRequest, UUID> {

    List<PersonalPrivacyRequest> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId);

    Optional<PersonalPrivacyRequest> findByIdAndTenantIdAndUserId(UUID id, Long tenantId, Long userId);

    Optional<PersonalPrivacyRequest> findFirstByTenantIdAndUserIdAndRequestTypeAndRequestStateOrderByCreatedAtDesc(
            Long tenantId, Long userId, String requestType, String requestState);
}
