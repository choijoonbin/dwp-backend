package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.GovernedRouteAuthorityDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GovernedRouteAuthorityService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GovernedRouteAuthorityService.class);

    private final ObjectProvider<GovernedRouteAuthorityPort> authorityPorts;

    public GovernedRouteAuthorityService(
            ObjectProvider<GovernedRouteAuthorityPort> authorityPorts) {
        this.authorityPorts = authorityPorts;
    }

    public GovernedRouteAuthorityDtos.AuthorityResult evaluate(
            GovernedRouteAuthorityDtos.EvaluateRequest request) {
        try {
            List<GovernedRouteAuthorityPort> ports = authorityPorts.orderedStream().toList();
            if (ports.size() != 1) {
                LOGGER.warn("Governed route authority requires exactly one registry adapter; found {}.",
                        ports.size());
                return GovernedRouteAuthorityDtos.AuthorityResult.unavailable(request);
            }
            GovernedRouteAuthorityDtos.AuthorityResult result = ports.getFirst().evaluate(request);
            return valid(request, result)
                    ? result
                    : GovernedRouteAuthorityDtos.AuthorityResult.unavailable(request);
        } catch (RuntimeException exception) {
            LOGGER.warn("Governed route authority evaluation failed closed for {}: {}",
                    request.routeContractKey(), exception.toString());
            return GovernedRouteAuthorityDtos.AuthorityResult.unavailable(request);
        }
    }

    private boolean valid(
            GovernedRouteAuthorityDtos.EvaluateRequest request,
            GovernedRouteAuthorityDtos.AuthorityResult result) {
        if (result == null || result.decision() == null
                || result.accessMode() != request.activeAccessMode()) {
            return false;
        }
        if (result.decision() == GovernedRouteAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) {
            return true;
        }
        if (!request.navigationContextId().equals(result.navigationContextId())) {
            return false;
        }
        if (blank(result.authRevision()) || blank(result.policyRevision())) return false;
        if (result.decision() != GovernedRouteAuthorityDtos.Decision.ALLOWED) {
            return true;
        }
        return !blank(result.contextKey())
                && result.accessSource() != null
                && !blank(result.routeGrantRef())
                && result.revalidateAt() != null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
