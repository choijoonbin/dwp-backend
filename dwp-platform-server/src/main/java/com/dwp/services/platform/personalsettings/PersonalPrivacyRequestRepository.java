package com.dwp.services.platform.personalsettings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonalPrivacyRequestRepository extends JpaRepository<PersonalPrivacyRequest, UUID> {

    @Query("""
            SELECT request
              FROM PersonalPrivacyRequest request
             WHERE request.tenantId = :tenantId AND request.userId = :userId
             ORDER BY CASE WHEN request.requestState = 'RECEIVED' THEN 0 ELSE 1 END,
                      request.createdAt DESC, request.id DESC
            """)
    List<PersonalPrivacyRequest> findOwnerPageWithOpenRequestsFirst(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            Pageable pageable);

    Optional<PersonalPrivacyRequest> findByIdAndTenantIdAndUserId(UUID id, Long tenantId, Long userId);

    Optional<PersonalPrivacyRequest> findFirstByTenantIdAndUserIdAndRequestTypeAndRequestStateOrderByCreatedAtDesc(
            Long tenantId, Long userId, String requestType, String requestState);
}
