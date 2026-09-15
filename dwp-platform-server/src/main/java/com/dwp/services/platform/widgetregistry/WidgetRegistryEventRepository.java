package com.dwp.services.platform.widgetregistry;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WidgetRegistryEventRepository extends JpaRepository<WidgetRegistryEvent, UUID> {
    List<WidgetRegistryEvent> findTop100ByOrderByRegistryRevisionDesc();
}
