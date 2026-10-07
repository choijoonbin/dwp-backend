package com.dwp.platform.contracts.hris.identity.v1;

/** Consumer-facing port; transport, actual native providers and production wiring remain unimplemented. */
@FunctionalInterface
public interface SelfContextPortV1 {
    SelfContextResolutionV1 resolve(SelfContextQueryV1 query);
}
