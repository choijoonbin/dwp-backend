package com.dwp.services.people.organization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

/**
 * Default-off release gate for organization publication.
 *
 * <p>G-05 and PS-03 still require immutable HCM + Security approval evidence. There is
 * deliberately no environment override: opening publication requires a reviewed code change
 * after those external gates are complete.</p>
 */
@Component
public final class OrganizationPublishRolloutGate {

    static final String DISABLED_REASON =
            "Organization publication is disabled until G-05 and PS-03 approvals are complete.";

    public void requireEnabled() {
        throw new BaseException(ErrorCode.FORBIDDEN, DISABLED_REASON);
    }
}
