package com.dwp.services.people.security;

import com.dwp.services.people.hr.HcmPopulationScopeService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HcmScopeSelectionValidatorPer008Test {

    @Test
    void revalidatesEachPer008ProfileAgainstItsExactOwnerPopulation() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        HcmPopulationScopeService.ResolvedPopulation team =
                mock(HcmPopulationScopeService.ResolvedPopulation.class);
        HcmPopulationScopeService.ResolvedPopulation operations =
                mock(HcmPopulationScopeService.ResolvedPopulation.class);
        when(populations.requireTeam()).thenReturn(team);
        when(populations.requireOperations("READ")).thenReturn(operations);
        HcmScopeSelectionValidator validator = new HcmScopeSelectionValidator(populations);

        validator.validate(team("route.hcm.team.performance.page", "PAGE"));
        validator.validate(team("route.hcm.team.performance.data", "DATA"));
        validator.validate(collection(
                "route.hcm.personal.performance-feedback.page", "PAGE"));
        validator.validate(collection(
                "route.hcm.personal.feedback-assignments-mine.data", "DATA"));
        validator.validate(request(false, true));
        validator.validate(request(false, false));
        validator.validate(request(true, true));
        validator.validate(request(true, false));
        validator.validate(assigned(false));
        validator.validate(assigned(true));
        validator.validate(release("self-feedback-release-reader",
                Set.of("SELF", "OBJECT")));
        validator.validate(release("manager-feedback-release-reader",
                Set.of("OBJECT", "TARGET_POPULATION")));
        validator.validate(release("operator-feedback-release-reader",
                Set.of("OBJECT", "TARGET_POPULATION")));

        verify(populations, times(5)).requireTeam();
        verify(populations, times(5)).requireTrustedScope(
                team, "hcm.team", "TARGET_POPULATION", "TEAM/ORG_UNIT",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION");
        verify(populations, times(5)).requireSelfScope("hcm.personal");
        verify(populations).requireOperations("READ");
        verify(populations).requireTrustedScope(
                operations, "hcm.operations", "TARGET_POPULATION", "NAMED_POPULATION");
    }

    @Test
    void rejectsSubmitRouteTargetDriftAndReleasePopulationWidening() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        HcmScopeSelectionValidator validator = new HcmScopeSelectionValidator(populations);
        HcmV3PepRegistry.RouteAuthority submit = assigned(true);

        assertThatThrownBy(() -> validator.validate(new HcmV3PepRegistry.RouteAuthority(
                submit.routeContractKey(), submit.routeKind(), submit.profileKey(),
                submit.readOnly(), submit.predicatePolicyKeys(), submit.targetBindingKinds(),
                submit.bindingKey(), submit.capabilityContractKey(), submit.activationPolicy(),
                submit.method(),
                "/api/people/v1/hris/performance/feedback-requests/{feedbackRequestId}",
                null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PER-008 assigned object scope profile changed.");

        assertThatThrownBy(() -> validator.validate(release(
                "self-feedback-release-reader",
                Set.of("SELF", "OBJECT", "TARGET_POPULATION"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PER-008 self release target binding changed.");
    }

    private HcmV3PepRegistry.RouteAuthority team(String route, String kind) {
        return authority(route, kind, "team-performance-reader", true,
                Set.of("predicate.team-target-population.v1"),
                Set.of("TARGET_POPULATION"), "hcm.team.workbench.view", "GET", null);
    }

    private HcmV3PepRegistry.RouteAuthority collection(String route, String kind) {
        return authority(route, kind, "self-performance-reader", true,
                Set.of("predicate.self-person.v1",
                        "predicate.hcm-performance-feedback-assignment.v1"),
                Set.of("SELF", "TARGET_POPULATION"),
                "hcm.personal.performance.view", "GET", null);
    }

    private HcmV3PepRegistry.RouteAuthority request(boolean action, boolean manager) {
        String route = action
                ? "route.hcm.performance.feedback-request-create.action"
                : "route.hcm.performance.feedback-request-options.data";
        return authority(route, action ? "ACTION" : "DATA",
                manager ? "manager-feedback-requester" : "self-feedback-requester",
                !action,
                manager
                        ? Set.of("predicate.team-target-population.v1",
                        "predicate.hcm-performance-feedback-request.v1")
                        : Set.of("predicate.self-person.v1",
                        "predicate.hcm-performance-feedback-request.v1"),
                manager ? Set.of("OBJECT", "TARGET_POPULATION")
                        : Set.of("SELF", "OBJECT"),
                manager ? "hcm.team.performance.review" : "per.feedback.request.create",
                action ? "POST" : "GET", null);
    }

    private HcmV3PepRegistry.RouteAuthority assigned(boolean submit) {
        String route = submit ? "route.hcm.personal.feedback-response-submit.action"
                : "route.hcm.personal.feedback-assignment-detail.data";
        String publicPath = submit
                ? "/api/people/v1/hris/performance/feedback-assignments/"
                + "{feedbackAssignmentId}/responses"
                : "/api/people/v1/hris/performance/feedback-assignments/"
                + "{feedbackAssignmentId}";
        return new HcmV3PepRegistry.RouteAuthority(
                route, submit ? "ACTION" : "DATA",
                submit ? "assigned-feedback-provider" : "assigned-feedback-reader",
                !submit, Set.of("predicate.hcm-performance-feedback-assignment.v1"),
                Set.of("OBJECT", "TARGET_POPULATION"), route + ".binding.01",
                submit ? "per.feedback.assigned.submit" : "hcm.personal.performance.view",
                null, submit ? "POST" : "GET", publicPath, null);
    }

    private HcmV3PepRegistry.RouteAuthority release(
            String profile,
            Set<String> targets) {
        return authority("route.hcm.performance.feedback-release.data", "DATA", profile,
                true, Set.of("predicate.hcm-performance-feedback-release.v1"), targets,
                "per.feedback.aggregate.view", "GET", null);
    }

    private HcmV3PepRegistry.RouteAuthority authority(
            String route,
            String kind,
            String profile,
            boolean readOnly,
            Set<String> predicates,
            Set<String> targets,
            String capability,
            String method,
            HcmV3PepRegistry.StepUpBinding stepUp) {
        return new HcmV3PepRegistry.RouteAuthority(
                route, kind, profile, readOnly, predicates, targets,
                route + ".binding.01", capability, null, method,
                "/api/people/per008", stepUp);
    }
}
