package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WidgetDefinitionVersionRepository extends JpaRepository<WidgetDefinitionVersion, UUID> {
    List<WidgetDefinitionVersion> findByDefinitionIdOrderByCreatedAtDesc(UUID definitionId);
    Optional<WidgetDefinitionVersion> findByDefinitionIdAndSemanticVersion(
            UUID definitionId, String semanticVersion);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from WidgetDefinitionVersion v where v.versionId = :id")
    Optional<WidgetDefinitionVersion> lockById(@Param("id") UUID id);
}
