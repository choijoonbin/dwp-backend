package com.dwp.services.platform.personalsettings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonalPrivacyRequestRepository extends JpaRepository<PersonalPrivacyRequest, UUID> {

    List<PersonalPrivacyRequest> findByTenantIdAndUserIdOrderByCreatedAtDescIdDesc(
            Long tenantId, Long userId, Pageable pageable);

    Optional<PersonalPrivacyRequest> findByIdAndTenantIdAndUserId(UUID id, Long tenantId, Long userId);

    Optional<PersonalPrivacyRequest> findFirstByTenantIdAndUserIdAndRequestTypeAndRequestStateOrderByCreatedAtDesc(
            Long tenantId, Long userId, String requestType, String requestState);
}
