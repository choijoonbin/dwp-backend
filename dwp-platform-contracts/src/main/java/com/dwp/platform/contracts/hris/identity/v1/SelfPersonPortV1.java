package com.dwp.platform.contracts.hris.identity.v1;

/** Current self person identity only. No caller-supplied actor/person, historical asOf or employment selector. */
@FunctionalInterface
public interface SelfPersonPortV1 {
    SelfPersonResolutionV1 resolve();
}
