package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.security.Hrm012ReceiptFirstStepUpClient;
import com.dwp.services.people.security.Hrm012ReceiptFirstStepUpDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkforceIngestionPostReceiptExactEqualityTest {
    private static final Set<String> NON_EQUALITY_EVIDENCE = Set.of(
            "stepUpChallengeId", "stepUpExpiresAt");

    @Test
    void finalAuthRequestBindsReceiptAndEveryFrozenSelector() {
        var receipt = Hrm012TestFixtures.receipt(
                "RECEIVED", Hrm012TestFixtures.DIGEST_A, false);
        Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort port =
                mock(Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort.class);
        when(port.verify(any())).thenReturn(
                Hrm012TestFixtures.postReceiptVerdict(receipt));
        Hrm012WorkforceIngestionAuthority authority =
                new Hrm012WorkforceIngestionAuthority(request -> null, port,
                        java.time.Clock.fixed(Hrm012TestFixtures.NOW,
                                java.time.ZoneOffset.UTC));
        var identity = authority.verifyIdentity(
                Hrm012TestFixtures.actor(), Hrm012TestFixtures.marker(),
                Hrm012TestFixtures.headers());

        authority.postReceipt(identity, Hrm012TestFixtures.command(),
                Hrm012TestFixtures.headers(), receipt, Hrm012TestFixtures.DIGEST_B);

        ArgumentCaptor<Hrm012WorkforceIngestionPorts.PostReceiptAuthorityRequest> request =
                ArgumentCaptor.forClass(
                        Hrm012WorkforceIngestionPorts.PostReceiptAuthorityRequest.class);
        verify(port).verify(request.capture());
        var frozen = receipt.frozenAuthority();
        assertThat(request.getValue()).satisfies(value -> {
            assertThat(value.tenantId()).isEqualTo(frozen.tenantId());
            assertThat(value.numericUserId()).isEqualTo(frozen.numericUserId());
            assertThat(value.personPublicId()).isEqualTo(frozen.personPublicId());
            assertThat(value.contextKey()).isEqualTo(frozen.contextKey());
            assertThat(value.scopeRef()).isEqualTo(frozen.scopeRef());
            assertThat(value.populationScopeDigest())
                    .isEqualTo(frozen.populationScopeDigest());
            assertThat(value.authorizationRevision())
                    .isEqualTo(frozen.authorizationRevision());
            assertThat(value.populationRevision())
                    .isEqualTo(frozen.populationRevision());
            assertThat(value.fieldPolicyRevision())
                    .isEqualTo(frozen.fieldPolicyRevision());
            assertThat(value.featureDecisionRevision())
                    .isEqualTo(frozen.featureDecisionRevision());
            assertThat(value.purpose()).isEqualTo(frozen.purpose());
            assertThat(value.correlationId()).isEqualTo(frozen.correlationId());
            assertThat(value.idempotencyKeySha256())
                    .isEqualTo(frozen.idempotencyKeySha256());
            assertThat(value.canonicalRequestHash())
                    .isEqualTo(receipt.requestHash());
            assertThat(value.receiptPublicId()).isEqualTo(receipt.receiptPublicId());
        });
    }

    @Test
    void everyAttestedIdentityScopeRevisionAndReceiptMismatchFailsClosed()
            throws ReflectiveOperationException {
        var receipt = Hrm012TestFixtures.receipt(
                "RECEIVED", Hrm012TestFixtures.DIGEST_A, false);
        var baseline = Hrm012TestFixtures.postReceiptVerdict(receipt);
        Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort port =
                mock(Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort.class);
        Hrm012WorkforceIngestionAuthority authority =
                new Hrm012WorkforceIngestionAuthority(request -> null, port,
                        java.time.Clock.fixed(Hrm012TestFixtures.NOW,
                                java.time.ZoneOffset.UTC));
        var identity = authority.verifyIdentity(
                Hrm012TestFixtures.actor(), Hrm012TestFixtures.marker(),
                Hrm012TestFixtures.headers());

        for (RecordComponent component : baseline.getClass().getRecordComponents()) {
            if (NON_EQUALITY_EVIDENCE.contains(component.getName())) continue;
            when(port.verify(any())).thenReturn(changed(baseline, component.getName()));
            assertThatThrownBy(() -> authority.postReceipt(identity,
                    Hrm012TestFixtures.command(), Hrm012TestFixtures.headers(),
                    receipt, Hrm012TestFixtures.DIGEST_B))
                    .as(component.getName())
                    .isInstanceOf(Hrm012Problem.class)
                    .satisfies(error -> assertThat(((Hrm012Problem) error).status())
                            .isEqualTo(403));
        }
    }

    @Test
    void staleStepUpVerdictFailsClosed() throws ReflectiveOperationException {
        var receipt = Hrm012TestFixtures.receipt(
                "RECEIVED", Hrm012TestFixtures.DIGEST_A, false);
        Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort port =
                mock(Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort.class);
        when(port.verify(any())).thenReturn(with(
                Hrm012TestFixtures.postReceiptVerdict(receipt),
                "stepUpExpiresAt", Hrm012TestFixtures.NOW));
        Hrm012WorkforceIngestionAuthority authority =
                new Hrm012WorkforceIngestionAuthority(request -> null, port,
                        java.time.Clock.fixed(Hrm012TestFixtures.NOW,
                                java.time.ZoneOffset.UTC));
        var identity = authority.verifyIdentity(
                Hrm012TestFixtures.actor(), Hrm012TestFixtures.marker(),
                Hrm012TestFixtures.headers());

        assertThatThrownBy(() -> authority.postReceipt(identity,
                Hrm012TestFixtures.command(), Hrm012TestFixtures.headers(),
                receipt, Hrm012TestFixtures.DIGEST_B))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> assertThat(((Hrm012Problem) error).status())
                        .isEqualTo(403));
    }

    @Test
    void narrowCentralResponseIsNeverExpandedIntoACompleteVerdict() {
        Hrm012ReceiptFirstStepUpClient client = mock(Hrm012ReceiptFirstStepUpClient.class);
        when(client.verify(any())).thenReturn(new Hrm012ReceiptFirstStepUpDtos.VerifyResponse(
                "ALLOWED", "auth-v15", "duty-v16", "feature-v1", "challenge-id",
                OffsetDateTime.ofInstant(Hrm012TestFixtures.NOW.plusSeconds(20),
                        ZoneOffset.UTC)));
        Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort adapter =
                new Hrm012FailClosedWorkforceIngestionAdapters()
                        .postReceiptAuthority(client);
        var receipt = Hrm012TestFixtures.receipt(
                "RECEIVED", Hrm012TestFixtures.DIGEST_A, false);
        var authority = new Hrm012WorkforceIngestionAuthority(request -> null, adapter,
                java.time.Clock.fixed(Hrm012TestFixtures.NOW,
                        java.time.ZoneOffset.UTC));
        var identity = authority.verifyIdentity(
                Hrm012TestFixtures.actor(), Hrm012TestFixtures.marker(),
                Hrm012TestFixtures.headers());

        assertThatThrownBy(() -> authority.postReceipt(identity,
                Hrm012TestFixtures.command(), Hrm012TestFixtures.headers(), receipt,
                Hrm012TestFixtures.DIGEST_B))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> {
                    assertThat(((Hrm012Problem) error).status()).isEqualTo(503);
                    assertThat(((Hrm012Problem) error).retryable()).isTrue();
                });
        verify(client).verify(any());
    }

    private Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict changed(
            Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict source,
            String componentName) throws ReflectiveOperationException {
        RecordComponent[] components = source.getClass().getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            types[index] = component.getType();
            values[index] = component.getAccessor().invoke(source);
            if (component.getName().equals(componentName)) {
                values[index] = different(values[index], component.getType());
            }
        }
        Constructor<?> constructor = source.getClass().getDeclaredConstructor(types);
        return (Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict)
                constructor.newInstance(values);
    }

    private Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict with(
            Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict source,
            String componentName,
            Object replacement) throws ReflectiveOperationException {
        RecordComponent[] components = source.getClass().getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            types[index] = component.getType();
            values[index] = component.getName().equals(componentName)
                    ? replacement : component.getAccessor().invoke(source);
        }
        Constructor<?> constructor = source.getClass().getDeclaredConstructor(types);
        return (Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict)
                constructor.newInstance(values);
    }

    private Object different(Object value, Class<?> type) {
        if (type == String.class) return value + "-changed";
        if (type == long.class) return (Long) value + 1L;
        if (type == UUID.class) {
            return UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        }
        throw new AssertionError("No mismatch value for " + type);
    }
}
