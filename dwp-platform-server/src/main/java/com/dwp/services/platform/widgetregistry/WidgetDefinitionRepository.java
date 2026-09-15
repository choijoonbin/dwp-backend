package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WidgetDefinitionRepository extends JpaRepository<WidgetDefinition, UUID> {
    Optional<WidgetDefinition> findByDefinitionKey(String definitionKey);
    Page<WidgetDefinition> findByDefinitionState(String definitionState, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from WidgetDefinition d where d.definitionId = :id")
    Optional<WidgetDefinition> lockById(@Param("id") UUID id);
}
