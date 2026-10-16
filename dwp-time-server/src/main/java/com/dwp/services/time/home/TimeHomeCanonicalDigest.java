package com.dwp.services.time.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeMaterializationDigestV1;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Canonical digest of the closed response with its two circular reference fields removed. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.home.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class TimeHomeCanonicalDigest {

    String digest(HrisHomeContributionMaterializationV1 materialization) {
        return HrisHomeMaterializationDigestV1.digest(materialization);
    }
}
