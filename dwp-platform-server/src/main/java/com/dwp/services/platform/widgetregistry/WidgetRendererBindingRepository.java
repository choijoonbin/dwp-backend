package com.dwp.services.platform.widgetregistry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WidgetRendererBindingRepository extends JpaRepository<WidgetRendererBinding, UUID> {
    Optional<WidgetRendererBinding> findByRendererKeyAndBindingState(
            String rendererKey, String bindingState);
    List<WidgetRendererBinding> findByBindingStateOrderByRendererKey(String bindingState);
}
