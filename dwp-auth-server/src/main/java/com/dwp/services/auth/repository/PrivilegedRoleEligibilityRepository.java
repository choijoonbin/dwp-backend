package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.PrivilegedRoleEligibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrivilegedRoleEligibilityRepository
        extends JpaRepository<PrivilegedRoleEligibility, UUID> {

    List<PrivilegedRoleEligibility> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    Optional<PrivilegedRoleEligibility>
            findByPrivilegedRoleEligibilityIdAndTenantId(UUID eligibilityId, Long tenantId);

    @Query(value = """
            SELECT eligibility.*
              FROM com_privileged_role_eligibilities eligibility
             WHERE eligibility.tenant_id = :tenantId
               AND eligibility.lifecycle_state = 'ACTIVE'
               AND (eligibility.valid_from IS NULL OR eligibility.valid_from <= :now)
               AND (eligibility.valid_to IS NULL OR eligibility.valid_to > :now)
               AND (
                    (eligibility.principal_type = 'USER'
                        AND eligibility.principal_id = :userId)
                    OR
                    (eligibility.principal_type = 'GROUP'
                        AND EXISTS (
                            SELECT 1
                              FROM com_group_members membership
                             WHERE membership.tenant_id = eligibility.tenant_id
                               AND membership.group_id = eligibility.principal_id
                               AND membership.user_id = :userId
                        ))
               )
             ORDER BY eligibility.created_at DESC
            """, nativeQuery = true)
    List<PrivilegedRoleEligibility> findEffectiveForUser(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("now") Instant now);
}
