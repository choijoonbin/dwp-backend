package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013ControlPlaneAuthorityAdapterTest {
    private static final URI ENDPOINT = URI.create(
            "https://auth.internal/internal/auth/v1/hris-service-grant-authority/resolve");

    @Test
    void publicResolveBoundaryCannotDecoupleModeOrDrainFromTypedQuery() {
        var mapper = new ObjectMapper();
        var adapter = new AuthHrm013ServiceAuthorityAdapter(
                mapper, ENDPOINT, request -> null,
                (request, body, correlation) ->
                        Hrm013RuntimeTestSupport.headers(correlation));
        var query = new Hrm013ServiceAuthorityPort.Query(
                61L, "dwp-people-server", "SVC-PEP-APR-002",
                "HRIS_SEPARATION_TASK_WAIVER_REQUEST", 7L, 11L);

        assertThatThrownBy(() -> adapter.resolveResponse(
                query, HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE,
                null, UUID.randomUUID()))
                .isInstanceOf(Hrm013ServiceAuthorityPort.AuthorityRejectedException.class);
    }

    @Test
    void readinessObservationIsBoundToExactPeopleRoleAndTuple() {
        var mapper = new ObjectMapper();
        var query = new Hrm013ServiceAuthorityPort.Query(
                61L, "dwp-people-server", "SVC-PEP-APR-002",
                "HRIS_SEPARATION_TASK_WAIVER_REQUEST", 7L, 11L,
                HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE, null);
        var adapter = new AuthHrm013ServiceAuthorityAdapter(
                mapper, ENDPOINT, request -> {
                    Instant quiet = Instant.parse("2026-10-26T11:00:00Z");
                    var observation = new HrisServiceGrantAuthorityV1.ReadinessObservation(
                            61L, UUID.randomUUID(), 7L, 11L,
                            "dwp-people-server", "dwp-approval-server",
                            "SVC-PEP-APR-002", "HRIS_SEPARATION_TASK_WAIVER_REQUEST",
                            UUID.randomUUID(), 2L, quiet.minusSeconds(1),
                            HrisServiceGrantAuthorityV1.ServiceRole.CALLER, 0L,
                            quiet, quiet.plusSeconds(65), "a".repeat(64));
                    byte[] body = HrisServiceGrantAuthorityV1.encodeResponse(
                            mapper, HrisServiceGrantAuthorityV1.ResolveResponse.readiness(
                                    observation, request.correlationId()));
                    return new AuthHrm013ServiceAuthorityAdapter.Exchange(
                            200, body, "TLSv1.3",
                            "spiffe://dwp/service/dwp-auth-server", "auth.internal",
                            false, request.correlationId(), Instant.now());
                }, (request, body, correlation) ->
                Hrm013RuntimeTestSupport.headers(correlation));

        var response = adapter.resolveResponse(
                query, query.resolutionMode(), null, UUID.randomUUID());
        assertThat(response.status())
                .isEqualTo(HrisServiceGrantAuthorityV1.Status.READINESS_OBSERVED);
        assertThat(response.readinessObservation().attestingServiceRole())
                .isEqualTo(HrisServiceGrantAuthorityV1.ServiceRole.CALLER);
    }

    @Test
    void drainAdmissionMustAttestTheActualPeopleOwnerRole() {
        var mapper = new ObjectMapper();
        Instant rootCreated = Instant.parse("2026-10-26T10:00:00Z");
        UUID root = UUID.randomUUID();
        var fields = new HrisServiceGrantAuthorityV1.DrainFields(
                root, rootCreated, "a".repeat(64),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.ROOT_DELIVERY,
                root, 0L, "a".repeat(64));
        var query = new Hrm013ServiceAuthorityPort.Query(
                61L, "dwp-time-server", "SVC-PEP-HRM-008",
                "HRIS_SEPARATION_TIME_TASK_RECEIPT", 7L, 11L,
                HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING, fields);
        var keys = Hrm013RuntimeTestSupport.keys("time-key", "people-key");
        var adapter = new AuthHrm013ServiceAuthorityAdapter(
                mapper, ENDPOINT, request -> {
                    Instant recorded = Instant.parse("2026-10-26T11:00:00Z");
                    var grant = new HrisServiceGrantAuthorityV1.GrantAuthority(
                            UUID.randomUUID(), 3L, 61L, 7L, 11L, "ADMIT",
                            "dwp-time-server", "spiffe://dwp/service/dwp-time-server",
                            keys.callerJwk().kid(), keys.callerJwk(),
                            "dwp-people-server", "spiffe://dwp/service/dwp-people-server",
                            keys.ownerJwk().kid(), keys.ownerJwk(),
                            "SVC-PEP-HRM-008", "HRIS_SEPARATION_TIME_TASK_RECEIPT",
                            null, "b".repeat(64), recorded.minusSeconds(1));
                    var wrongRole = new HrisServiceGrantAuthorityV1.DrainAdmission(
                            UUID.randomUUID(), 1L, recorded.minusSeconds(1),
                            HrisServiceGrantAuthorityV1.ServiceRole.CALLER, 1L,
                            recorded, recorded.plusSeconds(30), recorded.plusSeconds(65));
                    byte[] body = HrisServiceGrantAuthorityV1.encodeResponse(
                            mapper, HrisServiceGrantAuthorityV1.ResolveResponse.admit(
                                    grant, wrongRole, request.correlationId()));
                    return new AuthHrm013ServiceAuthorityAdapter.Exchange(
                            200, body, "TLSv1.3",
                            "spiffe://dwp/service/dwp-auth-server", "auth.internal",
                            false, request.correlationId(), recorded);
                }, (request, body, correlation) ->
                Hrm013RuntimeTestSupport.headers(correlation));

        assertThatThrownBy(() -> adapter.requireSigningAuthority(query))
                .isInstanceOf(Hrm013ServiceAuthorityPort.AuthorityRejectedException.class);
    }
}
