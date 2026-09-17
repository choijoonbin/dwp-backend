package com.dwp.services.platform.personalsettings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonalPrivacyConsentRepository extends JpaRepository<PersonalPrivacyConsent, UUID> {

    List<PersonalPrivacyConsent> findTop50ByTenantIdAndUserIdOrderByOccurredAtDesc(
            Long tenantId, Long userId);

    Optional<PersonalPrivacyConsent> findTopByTenantIdAndUserIdAndPurposeKeyOrderByOccurredAtDesc(
            Long tenantId, Long userId, String purposeKey);
}
