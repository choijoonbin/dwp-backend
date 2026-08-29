package com.dwp.services.platform.widgetregistry.internal.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Monotonic safety interlock for the not-yet-authoritative Widget Registry runtime.
 *
 * <p>The receiver security fence remains installed, but this milestone deliberately has no
 * production permit path. Requesting activation therefore fails startup instead of silently
 * treating the presence of trust adapter beans as release approval.</p>
 */
@Component
public final class WidgetRegistryActivationInterlock implements InitializingBean {

    private static final String ACTIVATION_BLOCKED =
            "Widget Registry activation is blocked until production trust, mTLS, durable replay, "
                    + "authoritative handlers and ledger, KMS seal verification, and full contract "
                    + "parity are installed and approved";

    private final WidgetRegistryActivationProperties properties;

    public WidgetRegistryActivationInterlock(WidgetRegistryActivationProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.enabled()) {
            throw new IllegalStateException(ACTIVATION_BLOCKED);
        }
    }

    boolean permitsRequest() {
        return false;
    }
}
