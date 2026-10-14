package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HcmCompensationPlanningAuthorityAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-14T01:00:00Z");
    private static final UUID SUBJECT =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID AGGREGATE =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Set<String> PERMISSIONS = Set.of(
            "DATA.HRIS_COMPENSATION_PLAN:VIEW",
            "DATA.HRIS_COMPENSATION_PLAN:UPDATE",
            "ACTION.HRIS_COMPENSATION_PLAN_APPROVE:APPROVE");

    @Test
    void allTwelveOperationsRequireTheirExactV39RouteResourceAndAction() {
        for (Operation value : operations()) {
            HcmCompensationPlanningAuthorityAdapter adapter = adapter(value);
            CompensationPlanningAuthorityPort.AuthorityRequest request = request(value);

            CompensationPlanningAuthorityPort.AuthorityEvidence result =
                    adapter.authorize(request);

            assertThat(result.resource()).as(value.operation()).isEqualTo(value.resource());
            assertThat(result.action()).as(value.operation()).isEqualTo(value.action());
            assertThat(result.capability()).as(value.operation()).isEqualTo(value.capability());
            assertThat(result.populationScopeDigest()).matches("[0-9a-f]{64}");
            assertThat(result.fieldPolicyRevision()).isPositive();
            assertThat(result.authorizationRevision()).isPositive();
        }
    }

    @Test
    void forgedOperationResourceFailsBeforePopulationResolution() {
        Operation value = operations().getLast();
        HcmCompensationPlanningAuthorityAdapter adapter = adapter(value);
        CompensationPlanningAuthorityPort.AuthorityRequest request = request(value);
        var forged = new CompensationPlanningAuthorityPort.AuthorityRequest(
                request.tenantId(), request.actorId(), request.subjectPrincipalPublicId(),
                request.applicationEntitlement(),
                CompensationPlanningAuthorization.APPROVAL_RESOURCE,
                request.action(), request.operationId(), request.capability(),
                request.purposeCode(), request.aggregatePublicId());

        assertThatThrownBy(() -> adapter.authorize(forged))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    private HcmCompensationPlanningAuthorityAdapter adapter(Operation value) {
        HcmV3PepRegistry.StepUpBinding stepUp = value.highRisk()
                ? new HcmV3PepRegistry.StepUpBinding(
                "COMPENSATION_CYCLE", "cycleId", List.of(),
                "people", "dwp-people-server") : null;
        HcmV3PepRegistry.RouteAuthority route = new HcmV3PepRegistry.RouteAuthority(
                value.route(), value.kind(), "full-work", value.readOnly(),
                Set.of(HcmCompensationPlanningAuthorityAdapter.PREDICATE),
                value.aggregateRequired()
                        ? Set.of("OBJECT", "TARGET_POPULATION")
                        : Set.of("TARGET_POPULATION"),
                value.route() + ".binding.01", value.capability(),
                value.highRisk() ? "STEPUP-MGMT-HIGH-V1" : "ACTIVE",
                value.readOnly() ? "GET" : "POST", value.path(), stepUp,
                value.readOnly() ? "hcm.compensation-planning.v1" : null,
                value.readOnly() ? "HcmCompensationPlanningV1" : null);
        HcmPepContext.Evidence evidence = new HcmPepContext.Evidence(
                route, "psr-" + "c".repeat(64),
                OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC),
                "psc-" + "a".repeat(64), "hcm-scope-" + "b".repeat(40), "111");
        PeopleRequestContext.Actor actor = new PeopleRequestContext.Actor(
                19L, 71L, SUBJECT, Set.of("HR_ADMIN"), PERMISSIONS);
        return new HcmCompensationPlanningAuthorityAdapter(
                () -> evidence, () -> actor,
                ignored -> new CompensationPlanningAuthorityPort.TargetPopulation(
                        true, Set.of(), Set.of(), Set.of()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CompensationPlanningAuthorityPort.AuthorityRequest request(Operation value) {
        return new CompensationPlanningAuthorityPort.AuthorityRequest(
                71L, 19L, SUBJECT, CompensationPlanningAuthorization.APPLICATION,
                value.resource(), value.action(), value.operation(), value.capability(),
                CompensationPlanningAuthorization.PURPOSE,
                value.aggregateForbidden() ? null : AGGREGATE);
    }

    private List<Operation> operations() {
        String prefix = "/v1/hris/performance/compensation-planning/cycles";
        return List.of(
                op(CompensationPlanningService.QUERY_CYCLES, "VIEW",
                        CompensationPlanningAuthorization.DATA_RESOURCE,
                        CompensationPlanningAuthorization.VIEW_CAPABILITY,
                        "route.hcm.operations.compensation-planning.page", "PAGE",
                        true, true, false, prefix),
                op(CompensationPlanningService.QUERY_CYCLE, "VIEW",
                        CompensationPlanningAuthorization.DATA_RESOURCE,
                        CompensationPlanningAuthorization.VIEW_CAPABILITY,
                        "route.hcm.operations.compensation-cycle-detail.data", "DATA",
                        true, false, true, prefix + "/{cycleId}"),
                op(CompensationPlanningService.QUERY_PROPOSALS, "VIEW",
                        CompensationPlanningAuthorization.DATA_RESOURCE,
                        CompensationPlanningAuthorization.VIEW_CAPABILITY,
                        "route.hcm.operations.compensation-proposals.data", "DATA",
                        true, false, true, prefix + "/{cycleId}/proposals"),
                op(CompensationPlanningService.QUERY_PROPOSAL, "VIEW",
                        CompensationPlanningAuthorization.DATA_RESOURCE,
                        CompensationPlanningAuthorization.VIEW_CAPABILITY,
                        "route.hcm.operations.compensation-proposal-detail.data", "DATA",
                        true, false, true,
                        "/v1/hris/performance/compensation-planning/proposals/{proposalId}"),
                op(CompensationPlanningService.CREATE, "UPDATE",
                        CompensationPlanningAuthorization.DATA_RESOURCE,
                        CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                        "route.hcm.operations.compensation-cycle-create.action", "ACTION",
                        false, false, false, prefix),
                op(CompensationPlanningService.REVISE, "UPDATE",
                        CompensationPlanningAuthorization.DATA_RESOURCE,
                        CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                        "route.hcm.operations.compensation-cycle-revise.action", "ACTION",
                        false, false, true, prefix + "/{cycleId}/revisions"),
                op(CompensationPlanningService.BUDGET, "UPDATE",
                        CompensationPlanningAuthorization.DATA_RESOURCE,
                        CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                        "route.hcm.operations.compensation-budget-allocate.action", "ACTION",
                        false, false, true, prefix + "/{cycleId}/budget-allocations"),
                op(CompensationPlanningService.UPSERT_PROPOSAL, "UPDATE",
                        CompensationPlanningAuthorization.DATA_RESOURCE,
                        CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                        "route.hcm.operations.compensation-proposal-upsert.action", "ACTION",
                        false, false, true, prefix + "/{cycleId}/proposals/{proposalId}"),
                op(CompensationPlanningService.SUBMIT_PROPOSAL, "UPDATE",
                        CompensationPlanningAuthorization.DATA_RESOURCE,
                        CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                        "route.hcm.operations.compensation-proposal-submit.action", "ACTION",
                        false, false, true, prefix + "/{cycleId}/proposals/{proposalId}/submit"),
                op(CompensationPlanningService.APPROVE, "APPROVE",
                        CompensationPlanningAuthorization.APPROVAL_RESOURCE,
                        CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                        "route.hcm.operations.compensation-plan-approve.action", "ACTION",
                        false, false, true, prefix + "/{cycleId}/approve", true),
                op(CompensationPlanningService.PUBLISH, "APPROVE",
                        CompensationPlanningAuthorization.APPROVAL_RESOURCE,
                        CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                        "route.hcm.operations.compensation-snapshot-publish.action", "ACTION",
                        false, false, true, prefix + "/{cycleId}/approved-snapshots", true),
                op(CompensationPlanningService.CANCEL, "UPDATE",
                        CompensationPlanningAuthorization.DATA_RESOURCE,
                        CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                        "route.hcm.operations.compensation-cycle-cancel.action", "ACTION",
                        false, false, true, prefix + "/{cycleId}/cancel"));
    }

    private Operation op(
            String operation, String action, String resource, String capability,
            String route, String kind, boolean readOnly, boolean aggregateForbidden,
            boolean aggregateRequired, String path) {
        return op(operation, action, resource, capability, route, kind, readOnly,
                aggregateForbidden, aggregateRequired, path, false);
    }

    private Operation op(
            String operation, String action, String resource, String capability,
            String route, String kind, boolean readOnly, boolean aggregateForbidden,
            boolean aggregateRequired, String path, boolean highRisk) {
        return new Operation(operation, action, resource, capability, route, kind,
                readOnly, aggregateForbidden, aggregateRequired, path, highRisk);
    }

    private record Operation(
            String operation,
            String action,
            String resource,
            String capability,
            String route,
            String kind,
            boolean readOnly,
            boolean aggregateForbidden,
            boolean aggregateRequired,
            String path,
            boolean highRisk) {
    }
}
