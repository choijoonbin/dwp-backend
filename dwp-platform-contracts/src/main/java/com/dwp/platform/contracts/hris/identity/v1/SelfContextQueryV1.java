package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Instant;

/** Closed typed request. No caller-supplied tenant, actor, person or identity headers. */
public record SelfContextQueryV1(SelfContextPurposeV1 purpose, Instant asOf,
                                SelfContextSelectorV1 selector) {
}
