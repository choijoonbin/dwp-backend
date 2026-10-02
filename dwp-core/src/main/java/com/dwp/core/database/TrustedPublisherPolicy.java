package com.dwp.core.database;

import java.util.Objects;
import java.util.Set;

/**
 * A credentialed, least-privilege feed identity that may publish a sealed
 * projection without becoming an application or migration authority.
 */
public record TrustedPublisherPolicy(
        String principal,
        Set<AuxiliaryRoleAclGuard.AllowedPrivilege> allowedAclPrivileges,
        Set<AuxiliaryRoleAclGuard.AllowedPrivilege> requiredAclPrivileges) {

    public static final TrustedPublisherPolicy NONE =
            new TrustedPublisherPolicy("", Set.of(), Set.of());

    public TrustedPublisherPolicy {
        principal = RuntimeAuxiliaryAuthorityPolicySupport.canonicalOptionalRole(
                "trusted publisher principal", principal);
        allowedAclPrivileges = Set.copyOf(Objects.requireNonNull(
                allowedAclPrivileges, "allowedAclPrivileges must not be null"));
        requiredAclPrivileges = Set.copyOf(Objects.requireNonNull(
                requiredAclPrivileges, "requiredAclPrivileges must not be null"));
        if (principal.isEmpty()
                && (!allowedAclPrivileges.isEmpty() || !requiredAclPrivileges.isEmpty())) {
            throw new IllegalArgumentException(
                    "Trusted publisher ACL privileges require a principal");
        }
        for (AuxiliaryRoleAclGuard.AllowedPrivilege privilege : allowedAclPrivileges) {
            if (!principal.equals(privilege.grantee())) {
                throw new IllegalArgumentException(
                        "Trusted publisher ACL privilege names another principal");
            }
        }
        if (!allowedAclPrivileges.containsAll(requiredAclPrivileges)) {
            throw new IllegalArgumentException(
                    "Required trusted publisher ACL privileges must be an allowed subset");
        }
    }
}
