package com.dwp.services.provider.rollout;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalSyntheticProductSurfaceBootstrapBoundaryTest {
    private static final String RUN_ID = "w1-20261002t010203z-0123abcd";
    private static final String TOKEN = "local-rollout-token-0123456789abcdef";
    private static final List<String> FLAGS = List.of(
            "access.product-surfaces.context-shadow.v1",
            "access.product-surfaces.capability-enforcement.hcm.v1",
            "ux.product-surfaces.hcm.v1");

    @Test
    void boundaryBeansAreAbsentByDefault() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(FeatureRolloutRepository.class,
                        () -> mock(FeatureRolloutRepository.class))
                .withBean(FeatureRolloutDecisionOutboxRepository.class,
                        () -> mock(FeatureRolloutDecisionOutboxRepository.class))
                .withUserConfiguration(
                        LocalSyntheticProductSurfaceBootstrapFilter.class,
                        LocalSyntheticProductSurfaceBootstrapService.class,
                        LocalSyntheticProductSurfaceBootstrapController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticProductSurfaceBootstrapFilter.class);
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticProductSurfaceBootstrapService.class);
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticProductSurfaceBootstrapController.class);
                });
    }

    @Test
    void enabledBoundaryRejectsShortTokenDuringContextStartup() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class,
                        () -> new ObjectMapper().findAndRegisterModules())
                .withUserConfiguration(LocalSyntheticProductSurfaceBootstrapFilter.class)
                .withPropertyValues(
                        "dwp.provider.synthetic-product-surface-bootstrap.enabled=true",
                        "dwp.environment=local",
                        "dwp.provider.synthetic-product-surface-bootstrap.token=short")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRequiresLocalEnvironmentAndLongUnpaddedToken() {
        assertThatThrownBy(() ->
                LocalSyntheticProductSurfaceBootstrapFilter.requireLocalConfiguration(
                        "production", TOKEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                LocalSyntheticProductSurfaceBootstrapFilter.requireLocalConfiguration(
                        "local", "short"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                LocalSyntheticProductSurfaceBootstrapFilter.requireLocalConfiguration(
                        "local", TOKEN + " "))
                .isInstanceOf(IllegalStateException.class);
        LocalSyntheticProductSurfaceBootstrapFilter.requireLocalConfiguration("local", TOKEN);
    }

    @Test
    void transportAcceptsOnlyLoopbackAndExactPurposeToken() throws Exception {
        var filter = new LocalSyntheticProductSurfaceBootstrapFilter(
                "local", TOKEN, new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest accepted = request(
                LocalSyntheticProductSurfaceBootstrapFilter.PATH, "127.0.0.1", TOKEN);
        MockFilterChain acceptedChain = new MockFilterChain();
        filter.doFilter(accepted, new MockHttpServletResponse(), acceptedChain);
        assertThat(acceptedChain.getRequest()).isSameAs(accepted);

        for (MockHttpServletRequest rejected : List.of(
                request(LocalSyntheticProductSurfaceBootstrapFilter.PATH,
                        "203.0.113.10", TOKEN),
                request(LocalSyntheticProductSurfaceBootstrapFilter.PATH,
                        "127.0.0.1", "x".repeat(40)),
                request("GET", LocalSyntheticProductSurfaceBootstrapFilter.PATH,
                        "127.0.0.1", TOKEN),
                duplicateTokenRequest())) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(rejected, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }

        MockHttpServletRequest unrelated = request(
                "/actuator/health", "203.0.113.10", "wrong");
        MockFilterChain unrelatedChain = new MockFilterChain();
        filter.doFilter(unrelated, new MockHttpServletResponse(), unrelatedChain);
        assertThat(unrelatedChain.getRequest()).isSameAs(unrelated);
    }

    @Test
    void createsExactOffRolloutAndEnforcesFreshOneShotTenantMapping() {
        Fixture fixture = fixture();
        when(fixture.jdbc.queryForObject(anyString(), eq(Integer.class),
                any(Object[].class))).thenReturn(0, 1);
        for (int index = 0; index < FLAGS.size(); index++) {
            FeatureRolloutRepository.FlagRow flag = flag(FLAGS.get(index), index);
            when(fixture.rollouts.flag(FLAGS.get(index))).thenReturn(Optional.of(flag));
            when(fixture.decisions.revision(flag.flagId())).thenReturn(index + 7L);
        }
        var request = request(
                "000",
                UUID.fromString("8f624772-fd7f-52b6-9792-bb3292616881"),
                41L,
                "w1-b-0123abcd");

        var response = fixture.service.bootstrap(request);

        assertThat(response.hcmState()).isEqualTo("000");
        assertThat(response.rolloutRevisions()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of(
                        FLAGS.get(0), "rev-00000000000000000007",
                        FLAGS.get(1), "rev-00000000000000000008",
                        FLAGS.get(2), "rev-00000000000000000009"));
        assertThat(response.receiptSha256()).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> fixture.service.bootstrap(request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("fresh tenant identities");
    }

    @Test
    void createsExactOnRolloutThroughMakerCheckerActivationStateMachine() {
        Fixture fixture = fixture();
        when(fixture.jdbc.queryForObject(anyString(), eq(Integer.class),
                any(Object[].class))).thenReturn(0);
        FeatureRolloutRepository.RolloutRow rollout = rollout();
        for (int index = 0; index < FLAGS.size(); index++) {
            FeatureRolloutRepository.FlagRow flag = flag(FLAGS.get(index), index);
            when(fixture.rollouts.lockFlag(FLAGS.get(index))).thenReturn(Optional.of(flag));
        }
        when(fixture.rollouts.createRollout(
                any(FeatureRolloutRepository.FlagRow.class),
                any(UUID.class),
                any(FeatureRolloutDtos.CreateRolloutRequest.class),
                anyLong())).thenReturn(rollout);
        when(fixture.rollouts.submit(any(UUID.class), anyLong(), anyLong())).thenReturn(true);
        when(fixture.rollouts.rollout(any(UUID.class))).thenReturn(Optional.of(rollout));
        when(fixture.rollouts.decide(
                any(UUID.class), anyLong(), eq("APPROVED"), anyString(), anyLong()))
                .thenReturn(true);
        when(fixture.rollouts.activate(any(UUID.class), anyLong())).thenReturn(true);
        when(fixture.decisions.appendAllTenants(
                any(UUID.class), anyString(), eq("ENABLED"))).thenReturn(1L, 2L, 3L);

        var response = fixture.service.bootstrap(
                request(
                        "111",
                        UUID.fromString("5e1180fe-d98f-5bbc-bc19-60c21f69cd7e"),
                        42L,
                        "w1-a-0123abcd"));

        assertThat(response.hcmState()).isEqualTo("111");
        assertThat(response.rolloutRevisions().values())
                .containsExactlyInAnyOrder(
                        "rev-00000000000000000001",
                        "rev-00000000000000000002",
                        "rev-00000000000000000003");
    }

    @Test
    void rejectsRequestsBoundToAnotherSyntheticRunBeforeDatabaseAccess() {
        Fixture fixture = fixture();
        var request = new LocalSyntheticProductSurfaceBootstrapDtos.BootstrapRequest(
                "w1-20261002t010203z-feedface",
                UUID.randomUUID(),
                42L,
                "w1-a-feedface",
                "synthetic A",
                "111");

        assertThatThrownBy(() -> fixture.service.bootstrap(request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not bound to this runtime");
    }

    private static Fixture fixture() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FeatureRolloutRepository rollouts = mock(FeatureRolloutRepository.class);
        FeatureRolloutDecisionOutboxRepository decisions =
                mock(FeatureRolloutDecisionOutboxRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return new Fixture(
                jdbc,
                rollouts,
                decisions,
                new LocalSyntheticProductSurfaceBootstrapService(
                        jdbc, rollouts, decisions, objectMapper, RUN_ID));
    }

    private static FeatureRolloutRepository.FlagRow flag(String key, int index) {
        return new FeatureRolloutRepository.FlagRow(
                new UUID(0, index + 1L),
                key,
                key,
                key,
                "dwp-provider",
                "BOOLEAN",
                BooleanNode.FALSE,
                new ObjectMapper().createObjectNode(),
                "LOW",
                "ACTIVE",
                1L);
    }

    private static FeatureRolloutRepository.RolloutRow rollout() {
        return new FeatureRolloutRepository.RolloutRow(
                UUID.randomUUID(),
                UUID.randomUUID(),
                FLAGS.getFirst(),
                1,
                "synthetic",
                "APPROVED",
                BooleanNode.TRUE,
                new ObjectMapper().createObjectNode(),
                "ALL_AT_ONCE",
                0,
                null,
                null,
                "synthetic",
                9_100_001L,
                9_100_002L,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                3L);
    }

    private static LocalSyntheticProductSurfaceBootstrapDtos.BootstrapRequest request(
            String state, UUID providerTenantId, long authTenantId, String tenantKey) {
        return new LocalSyntheticProductSurfaceBootstrapDtos.BootstrapRequest(
                RUN_ID,
                providerTenantId,
                authTenantId,
                tenantKey,
                "Synthetic tenant",
                state);
    }

    private static MockHttpServletRequest request(String path, String remoteAddress, String token) {
        return request("POST", path, remoteAddress, token);
    }

    private static MockHttpServletRequest request(
            String method, String path, String remoteAddress, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        request.addHeader(LocalSyntheticProductSurfaceBootstrapFilter.TOKEN_HEADER, token);
        return request;
    }

    private static MockHttpServletRequest duplicateTokenRequest() {
        MockHttpServletRequest request = request(
                LocalSyntheticProductSurfaceBootstrapFilter.PATH, "127.0.0.1", TOKEN);
        request.addHeader(LocalSyntheticProductSurfaceBootstrapFilter.TOKEN_HEADER, TOKEN);
        return request;
    }

    private record Fixture(
            JdbcTemplate jdbc,
            FeatureRolloutRepository rollouts,
            FeatureRolloutDecisionOutboxRepository decisions,
            LocalSyntheticProductSurfaceBootstrapService service) {
    }
}
