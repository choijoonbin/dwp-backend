package com.dwp.services.people.integration;

import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.workforce.WorkforceAccessDtos;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalSyntheticPeopleWorkforceBootstrapBoundaryTest {

    private static final String RUN_ID = "w1-20261002t010203z-0123abcd";
    private static final String TOKEN = "local-people-token-0123456789abcdef";
    private static final long ACTOR_INTERNAL_WORKER_ID = 10L;
    private static final long TARGET_INTERNAL_WORKER_ID = 11L;
    private static final UUID PROVIDER_TENANT_ID =
            UUID.fromString("10000000-0000-0000-0000-0000000000aa");
    private static final UUID PERSON_ID =
            UUID.fromString("20000000-0000-0000-0000-0000000000aa");
    private static final UUID WORKER_ID =
            UUID.fromString("30000000-0000-0000-0000-0000000000aa");
    private static final UUID ASSIGNMENT_ID =
            UUID.fromString("40000000-0000-0000-0000-0000000000aa");
    private static final UUID LEGAL_EMPLOYER_ID =
            UUID.fromString("50000000-0000-0000-0000-0000000000aa");
    private static final UUID TARGET_PERSON_ID =
            UUID.fromString("20000000-0000-0000-0000-0000000000bb");
    private static final UUID TARGET_WORKER_ID =
            UUID.fromString("30000000-0000-0000-0000-0000000000bb");
    private static final UUID TARGET_ASSIGNMENT_ID =
            UUID.fromString("40000000-0000-0000-0000-0000000000bb");

    @AfterEach
    void clearRequestContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void boundaryBeansAreAbsentByDefault() {
        new ApplicationContextRunner()
                .withBean(HrisImportService.class, () -> mock(HrisImportService.class))
                .withBean(HrisIntegrationRepository.class,
                        () -> mock(HrisIntegrationRepository.class))
                .withBean(WorkforceAccessPolicyService.class,
                        () -> mock(WorkforceAccessPolicyService.class))
                .withBean(HcmPopulationScopeService.class,
                        () -> mock(HcmPopulationScopeService.class))
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withUserConfiguration(
                        LocalSyntheticPeopleWorkforceBootstrapFilter.class,
                        LocalSyntheticPeopleWorkforceBootstrapService.class,
                        LocalSyntheticPeopleWorkforceBootstrapController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticPeopleWorkforceBootstrapFilter.class);
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticPeopleWorkforceBootstrapService.class);
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticPeopleWorkforceBootstrapController.class);
                });
    }

    @Test
    void enabledConfigurationRequiresLocalEnvironmentAndLongUnpaddedToken() {
        assertThatThrownBy(() ->
                LocalSyntheticPeopleWorkforceBootstrapFilter.requireLocalConfiguration(
                        "production", TOKEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                LocalSyntheticPeopleWorkforceBootstrapFilter.requireLocalConfiguration(
                        "local", "short"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                LocalSyntheticPeopleWorkforceBootstrapFilter.requireLocalConfiguration(
                        "local", TOKEN + " "))
                .isInstanceOf(IllegalStateException.class);
        LocalSyntheticPeopleWorkforceBootstrapFilter.requireLocalConfiguration("local", TOKEN);
    }

    @Test
    void transportAcceptsOnlyExactPostLoopbackAndPurposeToken() throws Exception {
        var filter = new LocalSyntheticPeopleWorkforceBootstrapFilter(
                "local", TOKEN, new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest accepted = request("POST", "127.0.0.1", TOKEN);
        AtomicReference<Object> markerInsideChain = new AtomicReference<>();
        filter.doFilter(accepted, new MockHttpServletResponse(), (request, response) ->
                markerInsideChain.set(request.getAttribute(
                        LocalSyntheticPeopleWorkforceBootstrapPaths
                                .AUTHORIZED_REQUEST_ATTRIBUTE)));
        assertThat(markerInsideChain.get()).isEqualTo(Boolean.TRUE);
        assertThat(accepted.getAttribute(
                LocalSyntheticPeopleWorkforceBootstrapPaths.AUTHORIZED_REQUEST_ATTRIBUTE))
                .isNull();

        MockHttpServletRequest duplicateToken = request("POST", "127.0.0.1", TOKEN);
        duplicateToken.addHeader(
                LocalSyntheticPeopleWorkforceBootstrapFilter.TOKEN_HEADER, TOKEN);
        for (MockHttpServletRequest rejected : List.of(
                request("GET", "127.0.0.1", TOKEN),
                request("POST", "203.0.113.10", TOKEN),
                request("POST", "127.0.0.1", "x".repeat(40)),
                duplicateToken)) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(rejected, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void productionOwnersMaterializeImportPolicyPopulationAndAuthBinding() {
        HrisImportService imports = mock(HrisImportService.class);
        HrisIntegrationRepository repository = mock(HrisIntegrationRepository.class);
        WorkforceAccessPolicyService policies = mock(WorkforceAccessPolicyService.class);
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        UUID syncRunId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        when(imports.importSyntheticWorkdayFixture(anyString(), anyString()))
                .thenReturn(importResult(syncRunId, false));
        when(repository.isActiveTenantBinding(41L, PROVIDER_TENANT_ID)).thenReturn(true);
        when(repository.findWorkforceIdentity(41L, "E100001"))
                .thenReturn(Optional.of(identity()));
        when(repository.findWorkforceIdentity(41L, "E100002"))
                .thenReturn(Optional.of(targetIdentity()));
        when(policies.list()).thenReturn(List.of());
        when(policies.create(any(), anyString())).thenReturn(policy(policyId));
        when(populations.findOperations("READ")).thenReturn(Optional.of(population()));
        when(populations.containsWorker(any(), eq(TARGET_INTERNAL_WORKER_ID)))
                .thenReturn(true);
        var service = new LocalSyntheticPeopleWorkforceBootstrapService(
                imports, repository, policies, populations, RUN_ID);

        var response = service.bootstrap(request());

        assertThat(response.actorPersonPublicId()).isEqualTo(PERSON_ID);
        assertThat(response.actorWorkerPublicId()).isEqualTo(WORKER_ID);
        assertThat(response.actorAssignmentPublicId()).isEqualTo(ASSIGNMENT_ID);
        assertThat(response.actorLegalEmployerPublicId()).isEqualTo(LEGAL_EMPLOYER_ID);
        assertThat(response.actorWorkerNumber()).isEqualTo("E100001");
        assertThat(response.targetPersonPublicId()).isEqualTo(TARGET_PERSON_ID);
        assertThat(response.targetWorkerPublicId()).isEqualTo(TARGET_WORKER_ID);
        assertThat(response.targetAssignmentPublicId()).isEqualTo(TARGET_ASSIGNMENT_ID);
        assertThat(response.importedWorkerCount()).isEqualTo(3);
        assertThat(response.targetPopulationCount()).isEqualTo(2);
        assertThat(response.targetPopulationRevision()).isEqualTo(
                "abc123:true|[]|[DIRECTORY, EMPLOYMENT, WORKER_IDENTIFIERS]|READ");
        assertThat(response.receiptSha256()).matches("[0-9a-f]{64}");
        assertThat(response.authWorkforceBinding().endpoint())
                .isEqualTo("/internal/identity/v1/workforce-events");
        assertThat(response.authWorkforceBinding().tokenHeader())
                .isEqualTo("X-DWP-Identity-Sync-Token");
        assertThat(response.authWorkforceBinding().expectedAdministratorUserId())
                .isEqualTo(1001L);
        assertThat(response.authWorkforceBinding().event().personPublicId())
                .isEqualTo(PERSON_ID);
        assertThat(response.authWorkforceBinding().event().workEmail())
                .isEqualTo("minseo.kim@sk.com");
        LocalSyntheticPeopleWorkforceBootstrapService.ReceiptEvidence evidence =
                receiptEvidence(response);
        assertThat(LocalSyntheticPeopleWorkforceBootstrapService.receiptSha256(evidence))
                .isEqualTo(response.receiptSha256());
        assertThat(LocalSyntheticPeopleWorkforceBootstrapService.receiptSha256(
                copyEvidence(evidence, null, null, null, 2L, null, null, null)))
                .isNotEqualTo(response.receiptSha256());
        assertThat(LocalSyntheticPeopleWorkforceBootstrapService.receiptSha256(
                copyEvidence(evidence, null, null, null, null, 1L,
                        "tampered-population", null)))
                .isNotEqualTo(response.receiptSha256());
        assertThat(LocalSyntheticPeopleWorkforceBootstrapService.receiptSha256(
                copyEvidence(evidence, null, UUID.randomUUID(), UUID.randomUUID(),
                        null, null, null, null)))
                .isNotEqualTo(response.receiptSha256());
        assertThat(LocalSyntheticPeopleWorkforceBootstrapService.receiptSha256(
                copyEvidence(evidence, 1002L, null, null, null, null, null, null)))
                .isNotEqualTo(response.receiptSha256());
        assertThat(LocalSyntheticPeopleWorkforceBootstrapService.receiptSha256(
                copyEvidence(evidence, null, null, null, null, null, null,
                        tamperedAuthBinding(response.authWorkforceBinding()))))
                .isNotEqualTo(response.receiptSha256());
        verify(imports).importSyntheticWorkdayFixture(
                eq("synthetic:" + RUN_ID + ":people-workforce"), anyString());
        verify(policies).create(any(), anyString());
        verify(populations).containsWorker(any(), eq(TARGET_INTERNAL_WORKER_ID));
        verify(populations).containsWorker(any(), eq(ACTOR_INTERNAL_WORKER_ID));
        assertThatThrownBy(PeopleRequestContext::require)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reusesAnExistingExactTenantAdminReadPolicy() {
        HrisImportService imports = mock(HrisImportService.class);
        HrisIntegrationRepository repository = mock(HrisIntegrationRepository.class);
        WorkforceAccessPolicyService policies = mock(WorkforceAccessPolicyService.class);
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        UUID policyId = UUID.randomUUID();
        when(imports.importSyntheticWorkdayFixture(anyString(), anyString()))
                .thenReturn(importResult(UUID.randomUUID(), false));
        when(repository.isActiveTenantBinding(41L, PROVIDER_TENANT_ID)).thenReturn(true);
        when(repository.findWorkforceIdentity(41L, "E100001"))
                .thenReturn(Optional.of(identity()));
        when(repository.findWorkforceIdentity(41L, "E100002"))
                .thenReturn(Optional.of(targetIdentity()));
        when(policies.list()).thenReturn(List.of(policy(policyId)));
        when(populations.findOperations("READ")).thenReturn(Optional.of(population()));
        when(populations.containsWorker(any(), eq(TARGET_INTERNAL_WORKER_ID)))
                .thenReturn(true);
        var service = new LocalSyntheticPeopleWorkforceBootstrapService(
                imports, repository, policies, populations, RUN_ID);

        var response = service.bootstrap(request());

        assertThat(response.importReplayed()).isFalse();
        assertThat(response.workforceAccessPolicyId()).isEqualTo(policyId);
    }

    @Test
    void rejectsAnExistingTenantAdminBoundaryThatDoesNotMatchTheFixtureContract() {
        HrisImportService imports = mock(HrisImportService.class);
        HrisIntegrationRepository repository = mock(HrisIntegrationRepository.class);
        WorkforceAccessPolicyService policies = mock(WorkforceAccessPolicyService.class);
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        when(imports.importSyntheticWorkdayFixture(anyString(), anyString()))
                .thenReturn(importResult(UUID.randomUUID(), false));
        when(repository.isActiveTenantBinding(41L, PROVIDER_TENANT_ID)).thenReturn(true);
        when(repository.findWorkforceIdentity(41L, "E100001"))
                .thenReturn(Optional.of(identity()));
        when(repository.findWorkforceIdentity(41L, "E100002"))
                .thenReturn(Optional.of(targetIdentity()));
        WorkforceAccessDtos.Policy conflicting = policy(UUID.randomUUID());
        when(policies.list()).thenReturn(List.of(new WorkforceAccessDtos.Policy(
                conflicting.policyId(), conflicting.subjectType(), conflicting.subjectRef(),
                conflicting.populationType(), conflicting.organizationId(),
                conflicting.organizationName(), conflicting.fieldGroups(),
                List.of("READ", "EXPORT"), conflicting.validFrom(), conflicting.validTo(),
                conflicting.lifecycleState(), conflicting.justification(), conflicting.version())));
        var service = new LocalSyntheticPeopleWorkforceBootstrapService(
                imports, repository, policies, populations, RUN_ID);

        assertThatThrownBy(() -> service.bootstrap(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with the active tenant-admin policy");
        verify(policies, never()).create(any(), anyString());
        verify(populations, never()).findOperations(anyString());
    }

    @Test
    void rejectsAnotherRunMismatchedTenantAReplayAndAnEmptyOwnerPopulation() {
        HrisImportService imports = mock(HrisImportService.class);
        HrisIntegrationRepository repository = mock(HrisIntegrationRepository.class);
        WorkforceAccessPolicyService policies = mock(WorkforceAccessPolicyService.class);
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        var service = new LocalSyntheticPeopleWorkforceBootstrapService(
                imports, repository, policies, populations, RUN_ID);
        var anotherRun = new LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapRequest(
                "w1-20261002t010203z-feedface", PROVIDER_TENANT_ID, 41L, 1001L);
        assertThatThrownBy(() -> service.bootstrap(anotherRun))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not bound to this runtime");

        assertThatThrownBy(() -> service.bootstrap(request()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("active People tenant");
        verify(imports, never()).importSyntheticWorkdayFixture(anyString(), anyString());

        when(repository.isActiveTenantBinding(41L, PROVIDER_TENANT_ID)).thenReturn(true);
        when(imports.importSyntheticWorkdayFixture(anyString(), anyString()))
                .thenReturn(importResult(UUID.randomUUID(), false));
        when(repository.findWorkforceIdentity(41L, "E100001"))
                .thenReturn(Optional.of(identity()));
        when(repository.findWorkforceIdentity(41L, "E100002"))
                .thenReturn(Optional.of(targetIdentity()));
        when(policies.list()).thenReturn(List.of(policy(UUID.randomUUID())));
        when(populations.findOperations("READ")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.bootstrap(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no live DIRECTORY READ population");
        assertThatThrownBy(PeopleRequestContext::require)
                .isInstanceOf(IllegalStateException.class);

        when(populations.findOperations("READ")).thenReturn(Optional.of(population(1L)));
        assertThatThrownBy(() -> service.bootstrap(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not the exact fixture");

        when(populations.findOperations("READ")).thenReturn(Optional.of(population()));
        when(populations.containsWorker(any(), eq(TARGET_INTERNAL_WORKER_ID)))
                .thenReturn(false);
        assertThatThrownBy(() -> service.bootstrap(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the owner population");
    }

    @Test
    void rejectsAnImportThatDoesNotContainExactlyThreeFixtureWorkers() {
        HrisImportService imports = mock(HrisImportService.class);
        HrisIntegrationRepository repository = mock(HrisIntegrationRepository.class);
        WorkforceAccessPolicyService policies = mock(WorkforceAccessPolicyService.class);
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        when(repository.isActiveTenantBinding(41L, PROVIDER_TENANT_ID)).thenReturn(true);
        when(imports.importSyntheticWorkdayFixture(anyString(), anyString()))
                .thenReturn(new HrisDtos.ImportResult(
                        UUID.randomUUID(), "workday-reference", "SUCCEEDED",
                        2, 2, 0, 0, false, true,
                        List.of("people.worker-projection.changed")));
        var service = new LocalSyntheticPeopleWorkforceBootstrapService(
                imports, repository, policies, populations, RUN_ID);

        assertThatThrownBy(() -> service.bootstrap(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact fixture evidence");
        verify(repository, never()).findWorkforceIdentity(any(), anyString());
    }

    @Test
    void rejectsASecondRunBoundBootstrapFromTheProductionImportReceipt() {
        HrisImportService imports = mock(HrisImportService.class);
        HrisIntegrationRepository repository = mock(HrisIntegrationRepository.class);
        WorkforceAccessPolicyService policies = mock(WorkforceAccessPolicyService.class);
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        when(repository.isActiveTenantBinding(41L, PROVIDER_TENANT_ID)).thenReturn(true);
        when(imports.importSyntheticWorkdayFixture(anyString(), anyString()))
                .thenReturn(importResult(UUID.randomUUID(), true));
        var service = new LocalSyntheticPeopleWorkforceBootstrapService(
                imports, repository, policies, populations, RUN_ID);

        assertThatThrownBy(() -> service.bootstrap(request()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("one-shot runtime boundary");
        verify(repository, never()).findWorkforceIdentity(any(), anyString());
        verify(policies, never()).list();
        assertThatThrownBy(PeopleRequestContext::require)
                .isInstanceOf(IllegalStateException.class);
    }

    private static HrisDtos.ImportResult importResult(UUID syncRunId, boolean replayed) {
        return new HrisDtos.ImportResult(
                syncRunId,
                "workday-reference",
                "SUCCEEDED",
                3,
                replayed ? 0 : 3,
                replayed ? 3 : 0,
                0,
                replayed,
                true,
                List.of("people.worker-projection.changed"));
    }

    private static LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapRequest request() {
        return new LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapRequest(
                RUN_ID, PROVIDER_TENANT_ID, 41L, 1001L);
    }

    private static HrisIntegrationRepository.WorkforceIdentityProjection identity() {
        return new HrisIntegrationRepository.WorkforceIdentityProjection(
                ACTOR_INTERNAL_WORKER_ID,
                PERSON_ID,
                WORKER_ID,
                ASSIGNMENT_ID,
                LEGAL_EMPLOYER_ID,
                "WD-WORKER-0001",
                "E100001",
                "Minseo Kim",
                "Minseo",
                "Kim",
                "minseo.kim@sk.com",
                "Network Operations Lead",
                "ko-KR",
                "ACTIVE",
                "2026-08-10T00:00:01Z");
    }

    private static HrisIntegrationRepository.WorkforceIdentityProjection targetIdentity() {
        return new HrisIntegrationRepository.WorkforceIdentityProjection(
                TARGET_INTERNAL_WORKER_ID,
                TARGET_PERSON_ID,
                TARGET_WORKER_ID,
                TARGET_ASSIGNMENT_ID,
                LEGAL_EMPLOYER_ID,
                "WD-WORKER-0002",
                "E100002",
                "Jiho Park",
                "Jiho",
                "Park",
                "jiho.park@sk.com",
                "AI Platform Engineer",
                "ko-KR",
                "ACTIVE",
                "2026-08-10T00:00:03Z");
    }

    private static WorkforceAccessDtos.Policy policy(UUID policyId) {
        return new WorkforceAccessDtos.Policy(
                policyId,
                "ROLE",
                "TENANT_ADMIN",
                "TENANT",
                null,
                null,
                List.of("DIRECTORY", "EMPLOYMENT", "WORKER_IDENTIFIERS"),
                List.of("READ"),
                null,
                null,
                "ACTIVE",
                "Run-bound local W1 People workforce acceptance boundary.",
                0L);
    }

    private static HcmPopulationScopeService.ResolvedPopulation population() {
        return population(2L);
    }

    private static HcmPopulationScopeService.ResolvedPopulation population(long count) {
        var actor = new HcmPopulationRepository.ActorWorkforce(
                ACTOR_INTERNAL_WORKER_ID,
                PERSON_ID,
                "Minseo Kim",
                "ASG-E100001-1",
                "Network Operations Lead",
                "Network Operations",
                0L,
                0L,
                0L);
        var scope = new HcmPopulationRepository.PopulationScope(
                ACTOR_INTERNAL_WORKER_ID,
                null,
                true,
                Set.of(),
                Set.of("DIRECTORY", "EMPLOYMENT", "WORKER_IDENTIFIERS"),
                "true|[]|[DIRECTORY, EMPLOYMENT, WORKER_IDENTIFIERS]|READ");
        return new HcmPopulationScopeService.ResolvedPopulation(
                actor, scope, new HcmPopulationRepository.PopulationEvidence(count, "abc123"));
    }

    private static LocalSyntheticPeopleWorkforceBootstrapService.ReceiptEvidence receiptEvidence(
            LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapResponse response) {
        return new LocalSyntheticPeopleWorkforceBootstrapService.ReceiptEvidence(
                response.runId(), response.providerTenantId(), response.tenantId(),
                response.administratorActorId(), response.actorPersonPublicId(),
                response.actorWorkerPublicId(), response.actorAssignmentPublicId(),
                response.actorLegalEmployerPublicId(), response.actorWorkerNumber(),
                response.targetPersonPublicId(), response.targetWorkerPublicId(),
                response.targetAssignmentPublicId(), response.syncRunId(),
                response.importReplayed(), response.importedWorkerCount(),
                response.workforceAccessPolicyId(), response.workforceAccessPolicyVersion(),
                response.targetPopulationCount(), response.targetPopulationRevision(),
                response.authWorkforceBinding());
    }

    private static LocalSyntheticPeopleWorkforceBootstrapService.ReceiptEvidence copyEvidence(
            LocalSyntheticPeopleWorkforceBootstrapService.ReceiptEvidence source,
            Long administratorActorId,
            UUID legalEmployerPublicId,
            UUID targetPersonPublicId,
            Long importedWorkerCount,
            Long targetPopulationCount,
            String targetPopulationRevision,
            LocalSyntheticPeopleWorkforceBootstrapDtos.AuthWorkforceBinding binding) {
        return new LocalSyntheticPeopleWorkforceBootstrapService.ReceiptEvidence(
                source.runId(), source.providerTenantId(), source.tenantId(),
                administratorActorId == null
                        ? source.administratorActorId() : administratorActorId,
                source.actorPersonPublicId(), source.actorWorkerPublicId(),
                source.actorAssignmentPublicId(), legalEmployerPublicId == null
                        ? source.actorLegalEmployerPublicId() : legalEmployerPublicId,
                source.actorWorkerNumber(), targetPersonPublicId == null
                        ? source.targetPersonPublicId() : targetPersonPublicId,
                source.targetWorkerPublicId(), source.targetAssignmentPublicId(),
                source.syncRunId(), source.importReplayed(), importedWorkerCount == null
                        ? source.importedWorkerCount() : importedWorkerCount,
                source.workforceAccessPolicyId(), source.workforceAccessPolicyVersion(),
                targetPopulationCount == null
                        ? source.targetPopulationCount() : targetPopulationCount,
                targetPopulationRevision == null
                        ? source.targetPopulationRevision() : targetPopulationRevision,
                binding == null ? source.authWorkforceBinding() : binding);
    }

    private static LocalSyntheticPeopleWorkforceBootstrapDtos.AuthWorkforceBinding
            tamperedAuthBinding(
                    LocalSyntheticPeopleWorkforceBootstrapDtos.AuthWorkforceBinding source) {
        var event = source.event();
        return new LocalSyntheticPeopleWorkforceBootstrapDtos.AuthWorkforceBinding(
                source.endpoint() + "/tampered",
                source.tokenHeader(),
                source.expectedAdministratorUserId(),
                new LocalSyntheticPeopleWorkforceBootstrapDtos.AuthWorkforceEvent(
                        event.eventId(), event.providerTenantId(), event.personPublicId(),
                        event.externalId(), event.workerNumber(), event.displayName(),
                        event.givenName(), event.familyName(), "tampered@example.com",
                        event.jobTitle(), event.preferredLocale(), event.workerStatus(),
                        event.sourceVersion()));
    }

    private static MockHttpServletRequest request(
            String method,
            String remoteAddress,
            String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method, LocalSyntheticPeopleWorkforceBootstrapFilter.PATH);
        request.setRemoteAddr(remoteAddress);
        request.addHeader(LocalSyntheticPeopleWorkforceBootstrapFilter.TOKEN_HEADER, token);
        return request;
    }
}
