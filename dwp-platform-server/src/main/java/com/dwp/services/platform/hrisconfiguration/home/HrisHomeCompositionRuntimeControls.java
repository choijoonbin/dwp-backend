package com.dwp.services.platform.hrisconfiguration.home;

import java.time.Duration;

/** Bounded fan-out controls for the four owner materializers. */
public record HrisHomeCompositionRuntimeControls(
        Duration ownerTimeout,
        int maxOwnerConcurrency) {

    public HrisHomeCompositionRuntimeControls {
        if (ownerTimeout == null || ownerTimeout.isZero() || ownerTimeout.isNegative()
                || ownerTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Home owner timeout must be in (0,5s]");
        }
        if (maxOwnerConcurrency < 1 || maxOwnerConcurrency > 4) {
            throw new IllegalArgumentException("Home owner concurrency must be in [1,4]");
        }
    }
}
