package com.dwp.services.auth.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Authentication-free projection used only to render the sign-in affordances.
 *
 * <p>Tenant identifiers, MFA posture, IdP identifiers, and provider metadata are deliberately
 * excluded. {@code NONE} is the fail-closed preference when the tenant context has no resolvable
 * login policy.</p>
 */
@Getter
@Builder
public class LoginOptionsResponse {

    private boolean localLoginAvailable;
    private boolean ssoLoginAvailable;
    private String preferredLoginType;
}
