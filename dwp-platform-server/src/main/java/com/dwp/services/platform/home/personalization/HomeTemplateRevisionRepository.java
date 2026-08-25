package com.dwp.services.platform.home.personalization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeTemplateRevisionRepository
        extends JpaRepository<HomeTemplateRevision, UUID> {
    Optional<HomeTemplateRevision> findTopByTemplateIdOrderByRevisionNumberDesc(
            UUID templateId);

    List<HomeTemplateRevision> findTop50ByTemplateIdAndTenantIdOrderByRevisionNumberDesc(
            UUID templateId, Long tenantId);
}
