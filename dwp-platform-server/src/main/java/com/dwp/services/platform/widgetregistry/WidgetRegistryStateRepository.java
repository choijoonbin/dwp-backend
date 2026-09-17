package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface WidgetRegistryStateRepository extends JpaRepository<WidgetRegistryState, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WidgetRegistryState s where s.environment = 'GLOBAL'")
    Optional<WidgetRegistryState> lockGlobal();
}
