package com.dwp.services.platform.widgetregistry.internal.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Immutable, default-deny activation request for the Widget Registry plane. */
@Component
public final class WidgetRegistryActivationProperties {

    private final boolean enabled;

    public WidgetRegistryActivationProperties(
            @Value("${dwp.platform.widget-registry-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }
}
