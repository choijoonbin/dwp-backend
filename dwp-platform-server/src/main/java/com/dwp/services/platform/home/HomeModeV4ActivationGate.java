package com.dwp.services.platform.home;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime interlock for the mode-scoped Home contract.
 *
 * <p>The operator request alone never opens the contract. The activation coordinator must first
 * commit the legacy view continuity transaction; only then are capabilities and writes exposed.</p>
 */
@Component
public class HomeModeV4ActivationGate {
    private final boolean requested;
    private final AtomicBoolean continuityReady = new AtomicBoolean(false);

    public HomeModeV4ActivationGate(
            @Value("${dwp.platform.home.mode-v4-activation-enabled:false}") boolean requested) {
        this.requested = requested;
    }

    public boolean requested() {
        return requested;
    }

    public boolean active() {
        return requested && continuityReady.get();
    }

    public void markContinuityReady() {
        if (requested) continuityReady.set(true);
    }
}
