package com.dwp.services.time.workregime;

import static com.dwp.services.time.workregime.WorkRegimeModels.Duty.TIME_AUDITOR;
import static com.dwp.services.time.workregime.WorkRegimeModels.Duty.TIME_CONFIG_APPROVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import com.dwp.services.time.workregime.WorkRegimeModels.Duty;
import com.dwp.services.time.workregime.WorkRegimeOwnerAuthoritySource.VerifiedRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkRegimeOwnerAccessFilterTest {

    private static final String DECISION_REVISION = "psr-" + "a".repeat(64);
    private static final String PLAN_ID = "3f9e07bc-e6a9-4bf8-84d4-b9b8a9fbeea0";

    private WorkRegimeOwnerAuthoritySource authoritySource;
    private OwnerProbeService service;
    private OwnerProbeController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authoritySource = mock(WorkRegimeOwnerAuthoritySource.class);
        service = mock(OwnerProbeService.class);
        controller = new OwnerProbeController(service);
        var filter = new WorkRegimeOwnerAccessFilter(
                authoritySource, new ObjectMapper().findAndRegisterModules());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
            unmatchedOwnerRoutes() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "malformed aggregate id",
                        "POST",
                        "/v1/hris/work-plans/not-a-uuid/simulations"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "unregistered method",
                        "DELETE",
                        "/v1/hris/work-plans"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "unregistered action",
                        "POST",
                        "/v1/hris/work-plans/" + PLAN_ID + "/actions/delete"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unmatchedOwnerRoutes")
    void malformedOrUnmatchedOwnerRouteFailsClosedBeforeAuthorityResolution(
            String ignored, String method, String path) throws Exception {
        var request = switch (method) {
            case "POST" -> post(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException("unsupported test method");
        };

        mockMvc.perform(request)
                .andExpect(status().isForbidden());

        verifyNoInteractions(authoritySource);
        assertThat(controller.ownerCalls()).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void emptyAuthorityFailsClosedWithoutCallingOwnerController() throws Exception {
        when(authoritySource.verify(any(HttpServletRequest.class), eq(WorkRegimeOwnerRoute.LIST)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/hris/work-plans"))
                .andExpect(status().isForbidden());

        verify(authoritySource, times(1))
                .verify(any(HttpServletRequest.class), eq(WorkRegimeOwnerRoute.LIST));
        assertThat(controller.ownerCalls()).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void authorityResolutionFailureIsUnavailableAndCallsNoOwnerController() throws Exception {
        when(authoritySource.verify(any(HttpServletRequest.class), eq(WorkRegimeOwnerRoute.LIST)))
                .thenThrow(new IllegalStateException("authority service unavailable"));

        mockMvc.perform(get("/v1/hris/work-plans"))
                .andExpect(status().isServiceUnavailable());

        verify(authoritySource, times(1))
                .verify(any(HttpServletRequest.class), eq(WorkRegimeOwnerRoute.LIST));
        assertThat(controller.ownerCalls()).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void expiredAuthorityCallsNoOwnerController() throws Exception {
        stub(WorkRegimeOwnerRoute.LIST, verified(
                Set.of(TIME_AUDITOR), WorkRegimeLifecycleGuard.REQUIRED_PURPOSE,
                false, Instant.now().minusSeconds(1)));

        mockMvc.perform(get("/v1/hris/work-plans"))
                .andExpect(status().isForbidden());

        assertThat(controller.ownerCalls()).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void wrongPurposeCallsNoOwnerController() throws Exception {
        stub(WorkRegimeOwnerRoute.LIST, verified(
                Set.of(TIME_AUDITOR), "PAY_CONFIGURATION", false,
                Instant.now().plusSeconds(60)));

        mockMvc.perform(get("/v1/hris/work-plans"))
                .andExpect(status().isForbidden());

        assertThat(controller.ownerCalls()).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void wrongDutyCallsNoOwnerController() throws Exception {
        stub(WorkRegimeOwnerRoute.CREATE_DRAFT, verified(
                Set.of(TIME_AUDITOR), WorkRegimeLifecycleGuard.REQUIRED_PURPOSE,
                false, Instant.now().plusSeconds(60)));

        mockMvc.perform(post("/v1/hris/work-plans/drafts"))
                .andExpect(status().isForbidden());

        assertThat(controller.ownerCalls()).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void validReadReachesOwnerControllerExactlyOnce() throws Exception {
        stub(WorkRegimeOwnerRoute.LIST, verified(
                Set.of(TIME_AUDITOR), WorkRegimeLifecycleGuard.REQUIRED_PURPOSE,
                false, Instant.now().plusSeconds(60)));

        mockMvc.perform(get("/v1/hris/work-plans"))
                .andExpect(status().isOk());

        verify(authoritySource, times(1))
                .verify(any(HttpServletRequest.class), eq(WorkRegimeOwnerRoute.LIST));
        assertThat(controller.ownerCalls()).isOne();
        verify(service, times(1)).call();
    }

    @Test
    void publishRequiresFreshStepUpBeforeCallingOwnerController() throws Exception {
        stub(WorkRegimeOwnerRoute.PUBLISH, verified(
                Set.of(TIME_CONFIG_APPROVER), WorkRegimeLifecycleGuard.REQUIRED_PURPOSE,
                false, Instant.now().plusSeconds(60)));

        mockMvc.perform(post("/v1/hris/work-plans/{id}/actions/publish", PLAN_ID))
                .andExpect(status().isForbidden());

        assertThat(controller.ownerCalls()).isZero();
        verifyNoInteractions(service);
    }

    @Test
    void steppedUpPublisherReachesOwnerControllerExactlyOnce() throws Exception {
        stub(WorkRegimeOwnerRoute.PUBLISH, verified(
                Set.of(TIME_CONFIG_APPROVER), WorkRegimeLifecycleGuard.REQUIRED_PURPOSE,
                true, Instant.now().plusSeconds(60)));

        mockMvc.perform(post("/v1/hris/work-plans/{id}/actions/publish", PLAN_ID))
                .andExpect(status().isOk());

        assertThat(controller.ownerCalls()).isOne();
        verify(service, times(1)).call();
    }

    @Test
    void nonOwnerPathIsUntouched() throws Exception {
        mockMvc.perform(get("/health-probe"))
                .andExpect(status().isOk());

        verify(authoritySource, never()).verify(any(), any());
        assertThat(controller.nonOwnerCalls()).isOne();
        assertThat(controller.ownerCalls()).isZero();
        verifyNoInteractions(service);
    }

    private void stub(WorkRegimeOwnerRoute route, VerifiedRequest verified) {
        when(authoritySource.verify(any(HttpServletRequest.class), eq(route)))
                .thenReturn(Optional.of(verified));
    }

    private static VerifiedRequest verified(
            Set<Duty> duties,
            String purpose,
            boolean stepUp,
            Instant revalidateAt) {
        var authority = new Authority(
                7L,
                41L,
                duties,
                Set.of("tenant:7"),
                purpose,
                DECISION_REVISION,
                stepUp,
                false);
        return new VerifiedRequest(
                authority,
                "tenant:7",
                DECISION_REVISION,
                revalidateAt,
                UUID.fromString("b4be4491-cf02-4b50-a3c4-2e59544e058b"));
    }

    @Profile("work-regime-owner-filter-test")
    @RestController
    private static final class OwnerProbeController {
        private final OwnerProbeService service;
        private final AtomicInteger ownerCalls = new AtomicInteger();
        private final AtomicInteger nonOwnerCalls = new AtomicInteger();

        private OwnerProbeController(OwnerProbeService service) {
            this.service = service;
        }

        @GetMapping("/v1/hris/work-plans")
        ResponseEntity<String> list() {
            ownerCalls.incrementAndGet();
            service.call();
            return ResponseEntity.ok("owner");
        }

        @PostMapping("/v1/hris/work-plans/drafts")
        ResponseEntity<String> createDraft() {
            ownerCalls.incrementAndGet();
            service.call();
            return ResponseEntity.ok("owner");
        }

        @PostMapping("/v1/hris/work-plans/{id}/actions/publish")
        ResponseEntity<String> publish() {
            ownerCalls.incrementAndGet();
            service.call();
            return ResponseEntity.ok("owner");
        }

        @GetMapping("/health-probe")
        ResponseEntity<String> healthProbe() {
            nonOwnerCalls.incrementAndGet();
            return ResponseEntity.ok("health");
        }

        int ownerCalls() {
            return ownerCalls.get();
        }

        int nonOwnerCalls() {
            return nonOwnerCalls.get();
        }
    }

    private interface OwnerProbeService {
        void call();
    }

    @Nested
    class ConditionalRegistration {
        private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withUserConfiguration(WorkRegimeOwnerAccessFilter.class);

        @Test
        void ownerBoundaryIsAbsentByDefault() {
            contextRunner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(WorkRegimeOwnerAccessFilter.class);
            });
        }

        @Test
        void enablingWithoutTrustedAuthoritySourceFailsApplicationWiringClosed() {
            contextRunner
                    .withPropertyValues("dwp.time.work-regime-api.enabled=true")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("WorkRegimeOwnerAuthoritySource");
                    });
        }
    }
}
