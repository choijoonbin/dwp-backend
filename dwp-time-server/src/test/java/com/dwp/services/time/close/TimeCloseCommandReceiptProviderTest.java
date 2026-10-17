package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dwp.services.time.api.HrisTimeApiException;
import com.dwp.services.time.api.HrisTimeProblemCode;
import com.dwp.services.time.close.TimeCloseModels.StoredReceipt;
import com.dwp.services.time.commandreceipt.CommandReceiptLookup;
import com.dwp.services.time.commandreceipt.VerifiedCommandReceiptRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TimeCloseCommandReceiptProviderTest {

    @Test
    void missingProviderAuthorityAndUnenrolledTenantStopBeforeOwnershipProbe() {
        TimeCloseReceiptOwnership ownership = mock(TimeCloseReceiptOwnership.class);
        TimeCloseActivationGate inactive = ignored -> false;
        TimeCloseCommandReceiptProvider provider = provider(ownership, inactive);

        assertThat(provider.owns(lookup(Map.of("timecard", new Object())))).isFalse();
        assertThat(provider.owns(lookup(Map.of("close", authority())))).isFalse();

        verifyNoInteractions(ownership);
    }

    @Test
    void activeEnrolledAuthorityProbesOnlyTheTenantScopedCloseOwner() {
        TimeCloseReceiptOwnership ownership = mock(TimeCloseReceiptOwnership.class);
        when(ownership.owns(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RECEIPT))
                .thenReturn(true);
        TimeCloseCommandReceiptProvider provider = provider(
                ownership, tenant -> tenant == TimeCloseTestFixtures.TENANT);

        assertThat(provider.providerId()).isEqualTo("close");
        assertThat(provider.owns(lookup(Map.of("close", authority())))).isTrue();

        verify(ownership).owns(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RECEIPT);
    }

    @Test
    void uniqueOwnerRevalidatesTheImmutableOriginBeforeReturningNativeDto() {
        TimeCloseReceiptOwnership ownership = mock(TimeCloseReceiptOwnership.class);
        StoredReceipt stored = TimeCloseTestFixtures.storedReceipt();
        when(ownership.reauthorizedRead(
                eq(TimeCloseTestFixtures.TENANT), eq(TimeCloseTestFixtures.RECEIPT), any()))
                .thenAnswer(invocation -> {
                    TimeCloseReceiptOwnership.OriginAuthority authority = invocation.getArgument(2);
                    return authority.permits(stored)
                            ? Optional.of(stored.receipt()) : Optional.empty();
                });
        TimeCloseCommandReceiptProvider provider = provider(
                ownership, tenant -> tenant == TimeCloseTestFixtures.TENANT);

        assertThat(provider.receipt(lookup(Map.of("close", authority()))))
                .isSameAs(stored.receipt());
    }

    @Test
    void missingOriginResponsibilityIsOpaqueEvenForAnExistingReceipt() {
        TimeCloseReceiptOwnership ownership = mock(TimeCloseReceiptOwnership.class);
        StoredReceipt stored = TimeCloseTestFixtures.storedReceipt();
        when(ownership.reauthorizedRead(
                eq(TimeCloseTestFixtures.TENANT), eq(TimeCloseTestFixtures.RECEIPT), any()))
                .thenAnswer(invocation -> {
                    TimeCloseReceiptOwnership.OriginAuthority authority = invocation.getArgument(2);
                    return authority.permits(stored)
                            ? Optional.of(stored.receipt()) : Optional.empty();
                });
        TimeCloseCommandReceiptProvider provider = provider(ownership, ignored -> true);
        VerifiedTimeCloseReceiptRequest denied = new VerifiedTimeCloseReceiptRequest(
                TimeCloseTestFixtures.TENANT, 72L, TimeCloseTestFixtures.SUBJECT,
                Set.of("APP.HCM:VIEW", "DATA.HRIS_COMMAND_RECEIPT:VIEW",
                        TimeCloseOwnerRoute.HANDOFF_CREATE.permission()),
                Set.of(), TimeCloseTestFixtures.POPULATION, 9L, 11L,
                TimeCloseTestFixtures.SCOPE_DIGEST, 13L, 17L,
                TimeCloseTestFixtures.DECISION_REVISION,
                TimeCloseTestFixtures.NOW.plusSeconds(300),
                TimeCloseTestFixtures.CORRELATION);

        assertThatThrownBy(() -> provider.receipt(
                lookup(Map.of("close", denied))))
                .isInstanceOfSatisfying(HrisTimeApiException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(HrisTimeProblemCode.TARGET_NOT_DISCLOSED));
    }

    @Test
    void commonOrNativeAuthorityAtExpiryBoundaryStopsBeforeActivationAndStorage() {
        TimeCloseReceiptOwnership ownership = mock(TimeCloseReceiptOwnership.class);
        TimeCloseActivationGate activation = mock(TimeCloseActivationGate.class);
        TimeCloseCommandReceiptProvider provider = provider(ownership, activation);
        CommandReceiptLookup commonExpired = lookup(
                Map.of("close", authority(TimeCloseTestFixtures.NOW.plusSeconds(1))),
                TimeCloseTestFixtures.NOW);
        CommandReceiptLookup nativeExpired = lookup(
                Map.of("close", authority(TimeCloseTestFixtures.NOW)),
                TimeCloseTestFixtures.NOW.plusSeconds(1));

        assertThat(provider.owns(commonExpired)).isFalse();
        assertThat(provider.owns(nativeExpired)).isFalse();
        assertOpaque(() -> provider.receipt(commonExpired));
        assertOpaque(() -> provider.receipt(nativeExpired));

        verifyNoInteractions(activation, ownership);
    }

    private CommandReceiptLookup lookup(Map<String, Object> evidence) {
        return lookup(evidence, TimeCloseTestFixtures.NOW.plusSeconds(300));
    }

    private CommandReceiptLookup lookup(
            Map<String, Object> evidence, Instant commonRevalidateAt) {
        return new CommandReceiptLookup(
                TimeCloseTestFixtures.TENANT,
                TimeCloseTestFixtures.RECEIPT,
                TimeCloseTestFixtures.CORRELATION,
                new VerifiedCommandReceiptRequest(
                        TimeCloseTestFixtures.TENANT,
                        TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.DECISION_REVISION,
                        commonRevalidateAt,
                        evidence));
    }

    private VerifiedTimeCloseReceiptRequest authority() {
        return authority(TimeCloseTestFixtures.NOW.plusSeconds(300));
    }

    private VerifiedTimeCloseReceiptRequest authority(Instant revalidateAt) {
        return new VerifiedTimeCloseReceiptRequest(
                TimeCloseTestFixtures.TENANT, 72L, TimeCloseTestFixtures.SUBJECT,
                Set.of("APP.HCM:VIEW", "DATA.HRIS_COMMAND_RECEIPT:VIEW",
                        TimeCloseOwnerRoute.HANDOFF_CREATE.permission()),
                Set.of(TimeCloseOwnerRoute.HANDOFF_CREATE.responsibility()),
                TimeCloseTestFixtures.POPULATION, 9L, 11L,
                TimeCloseTestFixtures.SCOPE_DIGEST, 13L, 17L,
                TimeCloseTestFixtures.DECISION_REVISION,
                revalidateAt,
                TimeCloseTestFixtures.CORRELATION);
    }

    private TimeCloseCommandReceiptProvider provider(
            TimeCloseReceiptOwnership ownership, TimeCloseActivationGate activation) {
        return new TimeCloseCommandReceiptProvider(
                ownership, activation,
                Clock.fixed(TimeCloseTestFixtures.NOW, ZoneOffset.UTC));
    }

    private void assertOpaque(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(HrisTimeApiException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(HrisTimeProblemCode.TARGET_NOT_DISCLOSED));
    }
}
