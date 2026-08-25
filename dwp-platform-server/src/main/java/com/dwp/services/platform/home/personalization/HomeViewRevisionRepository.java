package com.dwp.services.platform.home.personalization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeViewRevisionRepository extends JpaRepository<HomeViewRevision, UUID> {
    List<HomeViewRevision>
    findTop50ByViewIdAndTenantIdAndUserIdAndRestorableTrueOrderByRevisionNumberDesc(
            UUID viewId, Long tenantId, Long userId);
    Optional<HomeViewRevision> findByRevisionIdAndViewIdAndTenantIdAndUserId(
            UUID revisionId, UUID viewId, Long tenantId, Long userId);
    Optional<HomeViewRevision> findByTenantIdAndUserIdAndCommandId(
            Long tenantId, Long userId, UUID commandId);
    Optional<HomeViewRevision> findTopByViewIdOrderByRevisionNumberDesc(UUID viewId);
}
