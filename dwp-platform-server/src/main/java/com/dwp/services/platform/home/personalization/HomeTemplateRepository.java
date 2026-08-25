package com.dwp.services.platform.home.personalization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeTemplateRepository extends JpaRepository<HomeTemplate, UUID> {
    List<HomeTemplate> findTop100ByTenantIdOrderByUpdatedAtDesc(Long tenantId);
    List<HomeTemplate> findTop100ByTenantIdAndLifecycleStateOrderByUpdatedAtDesc(
            Long tenantId, String lifecycleState);
    Optional<HomeTemplate> findByTemplateIdAndTenantId(UUID templateId, Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select template from HomeTemplate template "
            + "where template.templateId = :templateId and template.tenantId = :tenantId")
    Optional<HomeTemplate> findOwnedForUpdate(UUID templateId, Long tenantId);

    long countByTenantId(Long tenantId);
}
