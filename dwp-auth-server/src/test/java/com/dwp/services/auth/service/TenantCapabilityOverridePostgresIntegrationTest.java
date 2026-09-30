package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.tenantcapabilityoverride.TenantCapabilityOverrideDtos;
import com.dwp.services.auth.tenantcapabilityoverride.TenantCapabilityOverrideRepository;
import com.dwp.services.auth.tenantcapabilityoverride.TenantCapabilityOverrideService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class TenantCapabilityOverridePostgresIntegrationTest {

    private static final Long TENANT = 1L;
    private static final Long REQUESTER = 1L;
    private static final Long REVIEWER = 2L;
    private static final Long ACTIVATOR = 3L;
    private static final Instant NOW = Instant.parse("2026-09-29T09:00:00Z");
    private static final String CONTRACT = "services.catalog.read";
    private static final String APP_RESOURCE = "APP.EMPLOYEE_SERVICES";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static ObjectMapper mapper;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        mapper = new ObjectMapper().findAndRegisterModules();
        ProductAuthorizationPostgresCatalogFixture.activateV3(source, jdbc, mapper);
    }

    @Test
    void fullWorkflowSuppressesPepAndSupportsExpiryInheritanceRevocationAndDbGuards() {
        TenantCapabilityOverrideRepository overrides =
                new TenantCapabilityOverrideRepository(jdbc, mapper);
        var authorization = mock(AppGovernanceAuthorization.class);
        when(authorization.appResourceKeys(TENANT, REQUESTER, "APP_OWNER"))
                .thenReturn(Set.of(APP_RESOURCE));
        when(authorization.appResourceKeys(TENANT, REVIEWER, "APP_ACCESS_APPROVER"))
                .thenReturn(Set.of(APP_RESOURCE));
        when(authorization.appResourceKeys(TENANT, ACTIVATOR, "APP_ACCESS_MANAGER"))
                .thenReturn(Set.of(APP_RESOURCE));
        when(authorization.appResourceKeys(
                org.mockito.ArgumentMatchers.eq(TENANT),
                org.mockito.ArgumentMatchers.anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    Long actor = invocation.getArgument(1);
                    String responsibility = invocation.getArgument(2);
                    if (REQUESTER.equals(actor) && "APP_OWNER".equals(responsibility)) {
                        return Set.of(APP_RESOURCE);
                    }
                    if (REVIEWER.equals(actor)
                            && "APP_ACCESS_APPROVER".equals(responsibility)) {
                        return Set.of(APP_RESOURCE);
                    }
                    if (ACTIVATOR.equals(actor)
                            && "APP_ACCESS_MANAGER".equals(responsibility)) {
                        return Set.of(APP_RESOURCE);
                    }
                    return Set.of();
                });
        var service = new TenantCapabilityOverrideService(
                overrides, mock(IdentityAuditService.class), authorization,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ProductAuthorizationAuthorityAdapter pep = pep(overrides, NOW);

        ProductSurfaceAuthorityDtos.AuthorityResult baseline = pep.evaluate(request(null));
        assertThat(baseline.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        String initialRevision = overrides.effectiveRevision(TENANT, NOW);

        var draft = service.create(TENANT, REQUESTER, "disable-draft",
                new TenantCapabilityOverrideDtos.CreateRequest(
                        CONTRACT, "DISABLED", NOW.plusSeconds(3600),
                        "Temporarily suppress this capability during access review."));
        assertThat(overrides.effectiveRevision(TENANT, NOW)).isNotEqualTo(initialRevision);
        assertThat(pep.evaluate(request(baseline.contextKey())).decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
        assertThatThrownBy(() -> service.create(TENANT, REQUESTER, "duplicate-draft",
                new TenantCapabilityOverrideDtos.CreateRequest(
                        CONTRACT, "DISABLED", NOW.plusSeconds(7200),
                        "A duplicate open suppression must be rejected by storage.")))
                .isInstanceOf(BaseException.class);

        var submitted = service.submit(TENANT, REQUESTER, "disable-submit",
                draft.overrideChangeId(), new TenantCapabilityOverrideDtos.VersionedCommand(
                        draft.version()));
        assertThatThrownBy(() -> service.decide(TENANT, REQUESTER, "self-review",
                draft.overrideChangeId(), new TenantCapabilityOverrideDtos.DecisionCommand(
                        submitted.version(), "APPROVE",
                        "A requester must never approve the same change.")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE com_tenant_capability_override_changes
                   SET lifecycle_state = 'APPROVED', approved_by = requested_by,
                       approved_at = CURRENT_TIMESTAMP
                 WHERE override_change_id = ?
                """, draft.overrideChangeId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        var approved = service.decide(TENANT, REVIEWER, "disable-approve",
                draft.overrideChangeId(), new TenantCapabilityOverrideDtos.DecisionCommand(
                        submitted.version(), "APPROVE",
                        "Independent application access review approved suppression."));
        assertThatThrownBy(() -> service.activate(TENANT, REVIEWER, "same-actor-activate",
                draft.overrideChangeId(), new TenantCapabilityOverrideDtos.ReasonedCommand(
                        approved.version(),
                        "The reviewer cannot activate the reviewed suppression.")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE com_tenant_capability_override_changes
                   SET lifecycle_state = 'ACTIVE', activated_by = approved_by,
                       activated_at = CURRENT_TIMESTAMP,
                       activation_receipt_id = gen_random_uuid()
                 WHERE override_change_id = ?
                """, draft.overrideChangeId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        var active = service.activate(TENANT, ACTIVATOR, "disable-activate",
                draft.overrideChangeId(), new TenantCapabilityOverrideDtos.ReasonedCommand(
                        approved.version(),
                        "Activate the independently approved tenant suppression."));
        assertThat(active.activationReceiptId()).isNotNull();
        assertThat(overrides.isDisabled(TENANT, CONTRACT, NOW)).isTrue();
        assertThat(pep.evaluate(request(null))).satisfies(result -> {
            assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
            assertThat(result.reasonCode()).isEqualTo("TENANT_CAPABILITY_DISABLED");
        });
        assertThat(overrides.isDisabled(TENANT, CONTRACT, NOW.plusSeconds(3601))).isFalse();
        assertThat(pep(overrides, NOW.plusSeconds(3601)).evaluate(request(null)).decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);

        var inherited = activate(service, "INHERIT", null, "restore");
        assertThat(inherited.desiredState()).isEqualTo("INHERIT");
        assertThat(overrides.isDisabled(TENANT, CONTRACT, NOW)).isFalse();
        assertThat(pep.evaluate(request(null)).decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);

        var disabledAgain = activate(
                service, "DISABLED", NOW.plusSeconds(1800), "revoke");
        assertThat(overrides.isDisabled(TENANT, CONTRACT, NOW)).isTrue();
        service.revoke(TENANT, ACTIVATOR, "disable-revoke",
                disabledAgain.overrideChangeId(), new TenantCapabilityOverrideDtos.ReasonedCommand(
                        disabledAgain.version(), "End the temporary tenant suppression."));
        assertThat(overrides.isDisabled(TENANT, CONTRACT, NOW)).isFalse();
    }

    private TenantCapabilityOverrideDtos.Change activate(
            TenantCapabilityOverrideService service,
            String desiredState,
            Instant validTo,
            String correlationPrefix) {
        var draft = service.create(TENANT, REQUESTER, correlationPrefix + "-draft",
                new TenantCapabilityOverrideDtos.CreateRequest(
                        CONTRACT, desiredState, validTo,
                        "Apply the independently governed tenant capability state."));
        var submitted = service.submit(TENANT, REQUESTER, correlationPrefix + "-submit",
                draft.overrideChangeId(), new TenantCapabilityOverrideDtos.VersionedCommand(
                        draft.version()));
        var approved = service.decide(TENANT, REVIEWER, correlationPrefix + "-approve",
                draft.overrideChangeId(), new TenantCapabilityOverrideDtos.DecisionCommand(
                        submitted.version(), "APPROVE",
                        "Independent application access review approved this state."));
        return service.activate(TENANT, ACTIVATOR, correlationPrefix + "-activate",
                draft.overrideChangeId(), new TenantCapabilityOverrideDtos.ReasonedCommand(
                        approved.version(), "Activate the independently approved state."));
    }

    private ProductAuthorizationAuthorityAdapter pep(
            TenantCapabilityOverrideRepository overrides, Instant instant) {
        var contracts = new ProductAuthorizationContractRepository(jdbc, mapper);
        var evidence = mock(ProductAuthorizationIdentityEvidenceService.class);
        AppGovernanceDtos.ResourceRole role = new AppGovernanceDtos.ResourceRole(
                "APP_CONFIG_ADMIN", "APP", APP_RESOURCE,
                UUID.nameUUIDFromBytes("RS_SERVICES".getBytes(StandardCharsets.UTF_8)),
                "RS_SERVICES", null);
        when(evidence.load(TENANT, 20L)).thenReturn(
                new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                        Set.of("ADMIN.SERVICE_CATALOG:VIEW"), Set.of(),
                        List.of(role), List.of(), "auth-postgres-revision"));
        return new ProductAuthorizationAuthorityAdapter(
                contracts, evidence, overrides, Clock.fixed(instant, ZoneOffset.UTC),
                "urn:dwp:acr:mfa");
    }

    private ProductSurfaceAuthorityDtos.EvaluateRequest request(String contextKey) {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(
                TENANT, 20L, "services", "services.management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.services.management.catalog.page", contextKey,
                null, null, null, List.of());
    }
}
