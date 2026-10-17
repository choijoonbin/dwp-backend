package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationPolicyRuntimeOwnerOverlayTest {

    @Test
    void sealsExactlyFourNonGrantingOwnerCorrectionsToV47() {
        assertThat(NavigationPolicyRuntimeOwnerOverlay.REVISION)
                .isEqualTo("R6C_RUNTIME_OWNER_OVERLAY_V1");
        assertThat(NavigationPolicyRuntimeOwnerOverlay.CHECKSUM)
                .isEqualTo("42ed206e45fab76f77725ba5d37543e06b8df62da7aa142bb620b032bf552dbd");
        assertThat(NavigationPolicyRuntimeOwnerOverlay.RECORDS)
                .extracting(NavigationPolicyRuntimeOwnerOverlay.OwnerRecord::nodeId)
                .containsExactly(
                        "BASE:team-time", "BASE:team-leave",
                        "BASE:time-leave-operations", "BASE:hr-operations-home");
        assertThat(NavigationPolicyRuntimeOwnerOverlay.RECORDS).allSatisfy(record -> {
            assertThat(record.effectiveRuntimeOwner()).isEqualTo("dwp-people-server");
            assertThat(record.registryVersion()).isEqualTo(47);
            assertThat(record.registryChecksum()).isEqualTo(
                    NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM);
            assertThat(record.evidenceDigest()).matches("^[0-9a-f]{64}$");
        });
    }

    @Test
    void emptyEligibilityCannotReceiveAuthorityAndCompatibilityNeedsCanonicalCandidate() {
        assertThat(NavigationPolicyRuntimeOwnerOverlay.seal(Map.of(), contracts())).isEmpty();
        assertThatThrownBy(() -> NavigationPolicyRuntimeOwnerOverlay.seal(
                Map.of("BASE:team-leave", List.of("/hr/team/absence")), contracts()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CANDIDATE_MISMATCH");
    }

    @Test
    void registryRouteAndOwnerMismatchFailClosed() {
        LinkedHashMap<String, NavigationPolicyRuntimeOwnerOverlay.PageAuthorityContract> changed =
                new LinkedHashMap<>(contracts());
        var original = changed.get("BASE:team-leave");
        changed.put("BASE:team-leave",
                new NavigationPolicyRuntimeOwnerOverlay.PageAuthorityContract(
                        original.nodeId(), original.canonicalPath(),
                        "route.hcm.team.absence.page", original.method(),
                        original.gatewayPath(), original.serviceKey(), original.servicePath(),
                        original.registryVersion(), original.registryChecksum()));
        assertThatThrownBy(() -> NavigationPolicyRuntimeOwnerOverlay.seal(
                Map.of("BASE:team-leave", List.of("/hr/team/leave")), changed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAGE_CONTRACT_MISMATCH");
    }

    private static Map<String, NavigationPolicyRuntimeOwnerOverlay.PageAuthorityContract>
    contracts() {
        LinkedHashMap<String, NavigationPolicyRuntimeOwnerOverlay.PageAuthorityContract> result =
                new LinkedHashMap<>();
        for (var record : NavigationPolicyRuntimeOwnerOverlay.RECORDS) {
            result.put(record.nodeId(),
                    new NavigationPolicyRuntimeOwnerOverlay.PageAuthorityContract(
                            record.nodeId(), record.canonicalPath(),
                            record.routeContractKey(), record.method(), record.gatewayPath(),
                            record.serviceKey(), record.servicePath(), record.registryVersion(),
                            record.registryChecksum()));
        }
        return Map.copyOf(result);
    }
}
