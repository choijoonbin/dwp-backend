package com.dwp.services.platform.home.runtime;

import java.util.Set;

/** Immutable receipt view used by admission controls independently of HTTP transport. */
interface HomeShadowReceipt {
    int schemaVersion();

    HomeReadModelShadowComparator.ShadowOutcome outcome();

    Set<HomeReadModelShadowComparator.ShadowReason> reasons();

    int mismatchCount();

    String homeMode();

    String deviceClass();

    String runtimeState();

    String rolloutRing();

    String rolloutRevision();
}
