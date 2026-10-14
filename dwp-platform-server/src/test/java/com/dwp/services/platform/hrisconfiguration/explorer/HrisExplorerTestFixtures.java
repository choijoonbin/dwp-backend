package com.dwp.services.platform.hrisconfiguration.explorer;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

final class HrisExplorerTestFixtures {

    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T04:00:00Z");
    static final UUID SUBJECT =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    static final String SCOPE = "hcm-scope-" + "a".repeat(40);
    static final String CONTEXT = "psc-" + "c".repeat(64);
    static final String DECISION = "psr-" + "d".repeat(64);
    static final String POPULATION = "b".repeat(64);
    static final String AUTHORITY_POPULATION =
            HrisExplorerAuthoritySeals.populationScopeDigest(SCOPE);

    private HrisExplorerTestFixtures() {
    }

    static AuthoritySnapshot snapshot() {
        Map<String, ExplorerAuthorization> decisions = new LinkedHashMap<>();
        HrisExplorerV43RouteAdapter.deployedRouteKeys().stream().sorted()
                .forEach(key -> decisions.put(
                        key, HrisExplorerV43RouteAdapter.authorization(key)));
        return snapshot(decisions);
    }

    static AuthoritySnapshot snapshot(Map<String, ExplorerAuthorization> decisions) {
        return new AuthoritySnapshot(
                42, SUBJECT, DECISION, CONTEXT, SCOPE,
                "HRIS_APP_DISCOVERY", POPULATION, 7, 11, 13, 17,
                NOW.plusMinutes(5), decisions);
    }
}
