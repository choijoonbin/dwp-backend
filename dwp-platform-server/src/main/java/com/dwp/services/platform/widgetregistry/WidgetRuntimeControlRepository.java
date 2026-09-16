package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WidgetRuntimeControlRepository extends JpaRepository<WidgetRuntimeControl, UUID> {
    List<WidgetRuntimeControl> findAllByOrderByCreatedAtDesc();

    @Query("""
            select c from WidgetRuntimeControl c
             where c.controlState = 'DISABLED'
               and (c.expiresAt is null or c.expiresAt > :now)
            """)
    List<WidgetRuntimeControl> findActiveDisabled(@Param("now") OffsetDateTime now);

    @Query("""
            select c from WidgetRuntimeControl c
             where c.controlState = 'ENABLED'
               and c.controlScope = 'RUNTIME_ACTION'
               and c.targetType = 'ACTION'
               and (c.expiresAt is null or c.expiresAt > :now)
            """)
    List<WidgetRuntimeControl> findEnabledActionApprovals(@Param("now") OffsetDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from WidgetRuntimeControl c
             where c.controlState = 'DISABLED'
               and c.expiresAt is not null
               and c.expiresAt <= :now
             order by c.createdAt
            """)
    List<WidgetRuntimeControl> findElapsedDisabled(@Param("now") OffsetDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from WidgetRuntimeControl c where c.controlId = :id")
    Optional<WidgetRuntimeControl> lockById(@Param("id") UUID id);
}
