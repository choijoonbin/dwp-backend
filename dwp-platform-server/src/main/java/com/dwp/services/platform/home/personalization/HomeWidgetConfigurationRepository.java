package com.dwp.services.platform.home.personalization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeWidgetConfigurationRepository
        extends JpaRepository<HomeWidgetConfiguration, UUID> {
    List<HomeWidgetConfiguration> findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
            UUID viewId, Long tenantId, Long userId);
    Optional<HomeWidgetConfiguration> findByViewIdAndTenantIdAndUserIdAndWidgetKey(
            UUID viewId, Long tenantId, Long userId, String widgetKey);
}
