package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.GovernedRouteAuthorityDtos;

/**
 * Adapter boundary for non-product governed route policies such as assigned review work.
 */
public interface GovernedRouteAuthorityPort {

    GovernedRouteAuthorityDtos.AuthorityResult evaluate(
            GovernedRouteAuthorityDtos.EvaluateRequest request);
}
