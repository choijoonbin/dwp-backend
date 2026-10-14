package com.dwp.services.platform.hrisconfiguration.explorer;

import java.util.Optional;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.*;

/**
 * Query implemented by the server-owned entitlement/scope/field-policy integration.
 * It is intentionally not a request-header adapter.
 */
public interface HrisExplorerAuthorityEvidenceQuery {

    Optional<AuthoritySnapshot> resolve(AuthorityRequest request);
}
