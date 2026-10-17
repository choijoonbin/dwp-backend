package com.dwp.services.time.timecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.api.HrisTimeApiException;
import com.dwp.services.time.api.HrisTimeProblemCode;
import com.dwp.services.time.commandreceipt.CommandReceiptLookup;
import com.dwp.services.time.commandreceipt.VerifiedCommandReceiptRequest;
import com.dwp.services.time.timecard.TimecardModels.PopulationKind;
import com.dwp.services.time.timecard.TimecardModels.TimeCommandReceiptV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardAction;
import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimecardCommandReceiptProviderTest {

    private static final UUID RECEIPT =
            UUID.fromString("17900000-0000-4000-8000-000000000031");
    private static final UUID CARD =
            UUID.fromString("17900000-0000-4000-8000-000000000032");
    private static final UUID CORRELATION =
            UUID.fromString("17900000-0000-4000-8000-000000000033");
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    private TimecardRepository repository;
    private TimecardService service;
    private TimecardCommandReceiptProvider provider;

    @BeforeEach
    void setUp() {
        repository = mock(TimecardRepository.class);
        service = mock(TimecardService.class);
        provider = new TimecardCommandReceiptProvider(repository, service);
    }

    @Test
    void ownershipProbeIsBoundToTheVerifiedTenantAndReceipt() {
        when(repository.findReceipt(41, RECEIPT)).thenReturn(Optional.of(mockStored()));

        assertThat(provider.providerId()).isEqualTo("timecard");
        assertThat(provider.owns(lookup())).isTrue();

        verify(repository).findReceipt(41, RECEIPT);
    }

    @Test
    void uniqueOwnerDelegatesOriginatingAuthorityRevalidationAndKeepsNativeDto() {
        VerifiedTimecardRequest authority = authority();
        TimeCommandReceiptV1 receipt = receipt();
        when(service.receipt(authority, RECEIPT)).thenReturn(receipt);

        assertThat(provider.receipt(lookup(authority))).isSameAs(receipt);

        verify(service).receipt(authority, RECEIPT);
    }

    @Test
    void failedOriginatingAuthorityIsReturnedAsOpaqueNotFound() {
        VerifiedTimecardRequest authority = authority();
        when(service.receipt(authority, RECEIPT)).thenThrow(
                new BaseException(ErrorCode.NOT_FOUND, "sensitive receipt details"));

        assertThatThrownBy(() -> provider.receipt(lookup(authority)))
                .isInstanceOfSatisfying(HrisTimeApiException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(HrisTimeProblemCode.TARGET_NOT_DISCLOSED));
    }

    private CommandReceiptLookup lookup() {
        return lookup(authority());
    }

    private CommandReceiptLookup lookup(VerifiedTimecardRequest authority) {
        return new CommandReceiptLookup(
                41, RECEIPT, CORRELATION,
                new VerifiedCommandReceiptRequest(
                        41, CORRELATION, authority.decisionRevision(),
                        authority.revalidateAt(), Map.of("timecard", authority)));
    }

    private TimecardModels.StoredTimecardReceipt mockStored() {
        return new TimecardModels.StoredTimecardReceipt(
                receipt(),
                authority().subjectPrincipalPublicId(),
                authority().authorizedWorkerPublicIds().iterator().next(),
                authority().populationScopeDigest(),
                authority().fieldPolicyRevision(),
                "TIME_TIMECARD",
                authority().authorizationRevision(),
                PopulationKind.TARGET_POPULATION,
                "DATA.HR_TIME:UPDATE");
    }

    private TimeCommandReceiptV1 receipt() {
        return new TimeCommandReceiptV1(
                RECEIPT,
                "TIMECARD_CORRECT",
                "TIME_TIMECARD_CORRECT",
                CARD,
                "SUCCEEDED",
                "a".repeat(64),
                NOW,
                NOW,
                "timecard:" + CARD + ":version:8");
    }

    private VerifiedTimecardRequest authority() {
        UUID subject = UUID.fromString("17900000-0000-4000-8000-000000000034");
        UUID actorWorker = UUID.fromString("17900000-0000-4000-8000-000000000035");
        UUID population = UUID.fromString("17900000-0000-4000-8000-000000000036");
        UUID worker = UUID.fromString("17900000-0000-4000-8000-000000000037");
        return new VerifiedTimecardRequest(
                41,
                72,
                subject,
                actorWorker,
                Set.of("DATA.HRIS_COMMAND_RECEIPT:VIEW", "DATA.HR_TIME:UPDATE"),
                Set.of(TimecardAction.RECEIPT_VIEW),
                PopulationKind.TARGET_POPULATION,
                population,
                9,
                11,
                "a".repeat(64),
                17,
                "COMMAND_STATUS",
                23,
                "psr-" + "b".repeat(64),
                NOW.plusSeconds(300),
                CORRELATION,
                Set.of(worker),
                Set.of(),
                Set.of());
    }
}
