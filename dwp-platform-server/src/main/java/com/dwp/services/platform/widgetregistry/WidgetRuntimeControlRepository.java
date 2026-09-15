package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WidgetRuntimeControlRepository extends JpaRepository<WidgetRuntimeControl, UUID> {
    List<WidgetRuntimeControl> findAllByOrderByCreatedAtDesc();
    List<WidgetRuntimeControl> findByControlState(String controlState);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from WidgetRuntimeControl c where c.controlId = :id")
    Optional<WidgetRuntimeControl> lockById(@Param("id") UUID id);
}
