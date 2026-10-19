package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.Per004TestFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class Per004ConfigurationRuntimeTest {
    @Test void exactUnionPreservesBothLegacyVariantsAndRedactsTransitContent() {
        var runtime = Per004ConfigurationRuntime.unavailable(MAPPER, CLOCK);
        for (String schema : SCHEMAS) {
            runtime.validateCreate(new CreateVersionCommand(Domain.PER, NOW, null, schema, 1, null, typed().ownerPayload()));
            assertThatThrownBy(() -> runtime.validateCreate(new CreateVersionCommand(Domain.PER, NOW, null, schema, 1,
                    new PayloadReference("ref_reference_123456789", PAYLOAD)))).isInstanceOf(BaseException.class);
        }
        for (Domain domain : Domain.values()) {
            String schema = domain == Domain.PER ? "legacy-other-per" : SCHEMA;
            runtime.validateCreate(new CreateVersionCommand(domain, NOW, null, schema, 1,
                    new PayloadReference("ref_reference_123456789", PAYLOAD)));
            assertThatThrownBy(() -> runtime.validateCreate(new CreateVersionCommand(domain, NOW, null, schema, 1,
                    new PayloadReference("ref_reference_123456789", PAYLOAD), typed().ownerPayload())))
                    .isInstanceOf(BaseException.class).hasMessageNotContaining("syntheticSecret");
        }
        assertThatThrownBy(() -> runtime.validateCreate(new CreateVersionCommand(Domain.PER, NOW, null, SCHEMA, 1,
                null, MAPPER.createObjectNode().put("body", "가".repeat(90000)))))
                .isInstanceOf(BaseException.class).hasMessageNotContaining("가");
        assertThat(typed().toString()).doesNotContain("syntheticSecret", "감사비저장");
        assertThat(new PrepareRequest(42, UUID.randomUUID(), UUID.randomUUID(), SCHEMA, 1, SCOPE, "create-key",
                EVIDENCE, UUID.randomUUID(), typed().ownerPayload()).toString()).doesNotContain("syntheticSecret");
    }

    @Test void featureOffBlocksSameKeyReplayBeforeReceiptLookupAndOtherPerStillUsesReferences() {
        var repository = spy(new ReferenceConfigurationTestRepository());
        var state = new AtomicReference<>("ACTIVE");
        FeatureDecisionPort features = request -> new FeatureDecision(request, FEATURE, FEATURE_ROUTE,
                state.get(), "ALLOWED", "revision-1", NOW.minusSeconds(1), NOW.plusSeconds(30));
        OwnerPort owners = mock(OwnerPort.class);
        Per004OwnerEvidenceStore evidence = mock(Per004OwnerEvidenceStore.class);
        var runtime = new Per004ConfigurationRuntime(features, owners, evidence, MAPPER, CLOCK);
        var service = new ReferenceConfigurationService(repository, MAPPER, CLOCK, runtime);
        var id = UUID.randomUUID();
        var command = command("create", "other-per-create-key", null);
        var body = new CreateVersionCommand(Domain.PER, NOW, null, "legacy-other-per", 1,
                new PayloadReference("ref_owner_1234567890", PAYLOAD));
        var created = service.createVersion(access(42, 101), command, id, body);
        assertThat(service.createVersion(access(42, 101), command, id, body)).isEqualTo(created);
        clearInvocations(repository);
        state.set("OFF");
        assertThatThrownBy(() -> service.createVersion(access(42, 101), command, id, body)).isInstanceOf(BaseException.class);
        verify(repository, never()).findReceipt(anyLong(), anyLong(), anyString(), anyString());
        verify(repository, never()).lockIdempotency(anyLong(), anyLong(), anyString(), anyString());
        verifyNoInteractions(owners, evidence);
    }

    @Test void missingWrongExpiredOrUngatedFeatureEvidenceNeverReachesOwnerOrStore() {
        var owners = mock(OwnerPort.class);
        var evidence = mock(Per004OwnerEvidenceStore.class);
        UUID id = UUID.randomUUID();
        for (String state : new String[]{"OFF", "UNGATED", "UNENROLLED", "ACTIVE"}) {
            FeatureDecisionPort features = request -> new FeatureDecision(request, FEATURE, FEATURE_ROUTE, state,
                    "ALLOWED", "revision-1", NOW.minusMinutes(1), NOW.minusSeconds(1));
            var runtime = new Per004ConfigurationRuntime(features, owners, evidence, MAPPER, CLOCK);
            assertThatThrownBy(() -> runtime.guard(access(42, 101), command("create", "create-key-1", null), id,
                    Domain.PER, "configuration.version.create", SCHEMA)).isInstanceOf(BaseException.class);
        }
        FeatureDecisionPort wrong = request -> active(new FeatureRequest(43, request.subjectPrincipalPublicId(),
                request.exactSharedActionRouteContractKey(), request.configurationId(), "PER", SCOPE,
                request.commandAuthorizationRevision(), request.correlationId()));
        assertThatThrownBy(() -> new Per004ConfigurationRuntime(wrong, owners, evidence, MAPPER, CLOCK)
                .guard(access(42, 101), command("create", "create-key-1", null), id, Domain.PER,
                        "configuration.version.create", SCHEMA)).isInstanceOf(BaseException.class);
        verifyNoInteractions(owners, evidence);
        assertThatThrownBy(() -> Per004ConfigurationRuntime.unavailable(MAPPER, CLOCK).guard(access(42, 101),
                command("create", "create-key-1", null), id, Domain.PER, "configuration.version.create", SCHEMA))
                .isInstanceOf(BaseException.class);
    }

    @Test void ownerOutageAndTupleMismatchCannotAllocateVersionOrDiscloseOwnerException() {
        var repo = mock(ReferenceConfigurationRepository.class);
        var owners = mock(OwnerPort.class);
        var evidence = mock(Per004OwnerEvidenceStore.class);
        var runtime = new Per004ConfigurationRuntime(Per004TestFixtures::active, owners, evidence, MAPPER, CLOCK);
        when(owners.prepare(any())).thenThrow(new IllegalStateException("syntheticSecret"));
        assertThatThrownBy(() -> runtime.create(repo, access(42, 101), command("create", "create-key-1", null),
                UUID.randomUUID(), typed(), UUID.randomUUID(), EVIDENCE)).isInstanceOf(BaseException.class)
                .hasMessageNotContaining("syntheticSecret").hasNoCause();
        verifyNoInteractions(repo);
        verify(evidence, never()).recordPreparation(any(), anyLong(), any(), any(), any(), any());
        doAnswer(invocation -> {
            PrepareRequest r = invocation.getArgument(0);
            return owner(ReceiptKind.PREPARATION, new OwnerTuple(r.tenantId(), r.configurationId(), null,
                    "PER", r.schemaKey(), r.schemaVersion(), "ref_owner_1234567890", PAYLOAD, r.scopeDigest()), UUID.randomUUID(), null);
        }).when(owners).prepare(any());
        assertThatThrownBy(() -> runtime.create(repo, access(42, 101), command("create", "create-key-1", null),
                UUID.randomUUID(), typed(), UUID.randomUUID(), EVIDENCE)).isInstanceOf(BaseException.class);
        verifyNoInteractions(repo);
        assertThat(ownerReference(PER_REFERENCE)).isTrue();
        assertThat(ownerReference(PER_REFERENCE.toUpperCase())).isFalse();
    }
}
