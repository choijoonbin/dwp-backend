package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.AccessContext;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.CommandContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceConfigurationCanonicalReceiptBridgeTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-15T04:00:00Z");
    private static final UUID SUBJECT =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID SCOPE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000042");
    private static final String ROUTE =
            "route.hcm.configuration.configuration-version-create.action";
    private static final String CONTEXT = "psc-" + "c".repeat(64);
    private static final String CONTEXT_SCOPE = "hcm-scope-" + "d".repeat(40);
    private static final String AUTHORITY_SCOPE_DIGEST =
            "4d6be313f8001ea3b7674fcaf69e7e5927ac10d868ad79f1e69fbc4aa4de934f";
    private static final String DECISION = "psr-" + "e".repeat(64);
    private static final long AUTHORITY_FIELD_REVISION = 4_663_670_355_146_917_040L;
    private static final long PUBLIC_FIELD_REVISION = 6_948_340_445_824_176L;
    private static final long AUTHORITY_AUTHORIZATION_REVISION = 1_991_387_878_219_104_048L;
    private static final long PUBLIC_AUTHORIZATION_REVISION = 796_842_921_344_816L;

    @Test
    void newSuccessDualWritesOnceWhileLegacyReplayAndMismatchRemainStable() {
        ReferenceConfigurationTestRepository repository =
                new ReferenceConfigurationTestRepository();
        ReferenceConfigurationService service = new ReferenceConfigurationService(
                repository, JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        UUID configurationId =
                UUID.fromString("00000000-0000-4000-8000-000000000501");
        CreateVersionCommand body = create("b".repeat(64));
        CommandContext sealed = command(ROUTE, CONTEXT, CONTEXT_SCOPE, DECISION);

        CommandReceipt first = service.createVersion(
                access(), sealed, configurationId, body);
        assertThat(first.fieldPolicyRevision()).isEqualTo(PUBLIC_FIELD_REVISION);
        assertThat(first.authorizationRevision()).isEqualTo(
                PUBLIC_AUTHORIZATION_REVISION);

        assertThat(repository.canonicalReceipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.receiptId()).isEqualTo(first.receiptId());
            assertThat(receipt.originatingAction())
                    .isEqualTo("configuration.version.create");
            assertThat(receipt.subjectPrincipalPublicId()).isEqualTo(SUBJECT);
            assertThat(receipt.populationScopeDigest()).isEqualTo("a".repeat(64));
            assertThat(receipt.authorityPopulationScopeDigest())
                    .isEqualTo(AUTHORITY_SCOPE_DIGEST)
                    .isNotEqualTo(receipt.populationScopeDigest());
            assertThat(receipt.purposeCode()).isEqualTo("HRIS_SYSTEM_ADMIN");
            assertThat(receipt.fieldPolicyRevision()).isEqualTo(PUBLIC_FIELD_REVISION);
            assertThat(receipt.authorityFieldPolicyRevision())
                    .isEqualTo(AUTHORITY_FIELD_REVISION);
            assertThat(receipt.authorizationRevision())
                    .isEqualTo(PUBLIC_AUTHORIZATION_REVISION);
            assertThat(receipt.authorityAuthorizationRevision())
                    .isEqualTo(AUTHORITY_AUTHORIZATION_REVISION);
            assertThat(receipt.originRouteContractKey()).isEqualTo(ROUTE);
            assertThat(receipt.originContextKey()).isEqualTo(CONTEXT);
            assertThat(receipt.originContextScopeKey()).isEqualTo(CONTEXT_SCOPE);
            assertThat(receipt.originDecisionRevision()).isEqualTo(DECISION);
            assertThat(receipt.requestDigest()).isEqualTo(first.requestDigest());
            assertThat(receipt.resultRef()).isEqualTo(configurationId + "/1");
        });

        CommandReceipt replay = service.createVersion(
                access(), command(null, null, null, null), configurationId, body);
        assertThat(replay).isEqualTo(first);
        assertThat(repository.canonicalReceipts).hasSize(1);
        assertThat(repository.versions).hasSize(1);

        assertThatThrownBy(() -> service.createVersion(
                access(), command(null, null, null, null), configurationId,
                create("f".repeat(64))))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("IDEMPOTENCY_PAYLOAD_MISMATCH");
        assertThat(repository.canonicalReceipts).hasSize(1);
        assertThat(repository.versions).hasSize(1);
    }

    @Test
    void canonicalAuthorityDigestIgnoresForgedPublicDigestAndRejectsNonCanonicalScope() {
        ReferenceConfigurationTestRepository repository =
                new ReferenceConfigurationTestRepository();
        ReferenceConfigurationService service = new ReferenceConfigurationService(
                repository, JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        service.createVersion(
                access("f".repeat(64), CONTEXT_SCOPE),
                command(ROUTE, CONTEXT, CONTEXT_SCOPE, DECISION),
                UUID.fromString("00000000-0000-4000-8000-000000000503"),
                create("b".repeat(64)));

        assertThat(repository.canonicalReceipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.populationScopeDigest()).isEqualTo("f".repeat(64));
            assertThat(receipt.authorityPopulationScopeDigest())
                    .isEqualTo(AUTHORITY_SCOPE_DIGEST);
        });

        ReferenceConfigurationTestRepository malformedRepository =
                new ReferenceConfigurationTestRepository();
        ReferenceConfigurationService malformedService = new ReferenceConfigurationService(
                malformedRepository, JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        String sourceScope = "scope-" + "d".repeat(40);
        assertThatThrownBy(() -> malformedService.createVersion(
                access("f".repeat(64), sourceScope),
                command(ROUTE, CONTEXT, sourceScope, DECISION),
                UUID.fromString("00000000-0000-4000-8000-000000000504"),
                create("b".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical current HCM eligibility scope");
        assertThat(malformedRepository.versions).isEmpty();
        assertThat(malformedRepository.canonicalReceipts).isEmpty();
    }

    private AccessContext access() {
        return access("a".repeat(64), CONTEXT_SCOPE);
    }

    private AccessContext access(String populationScopeDigest, String contextScope) {
        return new AccessContext(
                42, 101, SUBJECT,
                "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE", "TENANT_ADMIN",
                CONTEXT, contextScope,
                new PopulationScope(
                        ScopeType.LEGAL_ENTITY, SCOPE_ID,
                        populationScopeDigest, DECISION),
                AUTHORITY_FIELD_REVISION, AUTHORITY_AUTHORIZATION_REVISION,
                NOW.plusMinutes(5), Set.of(), Set.of(),
                "HRIS_SYSTEM_ADMIN");
    }

    private CommandContext command(
            String route, String context, String contextScope, String decision) {
        return new CommandContext(
                "reference-bridge-key-0001",
                UUID.fromString("00000000-0000-4000-8000-000000000502"),
                "HRIS_SYSTEM_ADMIN", null, null, null,
                route, context, contextScope, decision);
    }

    private CreateVersionCommand create(String digest) {
        return new CreateVersionCommand(
                Domain.SYS, NOW.plusMinutes(1), null,
                "employment-status", 1,
                new PayloadReference("ref_bridge_1234567890", digest));
    }
}
