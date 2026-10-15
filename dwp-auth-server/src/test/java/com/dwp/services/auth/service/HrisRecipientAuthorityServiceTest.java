package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.HrisRecipientAuthorityDtos;
import com.dwp.services.auth.repository.HrisRecipientAuthorityRepository;
import com.dwp.services.auth.repository.HrisRecipientAuthorityRepository.AuthorityRow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HrisRecipientAuthorityServiceTest {

    private static final long TENANT_ID = 17L;
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_PUBLIC_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID PRINCIPAL_PUBLIC_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID PERSON_PUBLIC_ID =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-10-15T04:00:00Z");

    private final HrisRecipientAuthorityRepository repository =
            mock(HrisRecipientAuthorityRepository.class);
    private final HrisRecipientAuthorityService service =
            new HrisRecipientAuthorityService(
                    repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void returnsOnePrivacyMinimizedThirtySecondAuthorityLeaseWithCanonicalDigest() {
        when(repository.resolve(TENANT_ID, PERSON_PUBLIC_ID))
                .thenReturn(List.of(validRow()));

        HrisRecipientAuthorityDtos.ResolveResponse response = service.resolve(request());

        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.tenantId()).isEqualTo(TENANT_ID);
        assertThat(response.personPublicId()).isEqualTo(PERSON_PUBLIC_ID);
        assertThat(response.userId()).isEqualTo(93L);
        assertThat(response.evidenceRevision()).isEqualTo(
                "baca3d85cfd073e33e91d5a0493cc454fd15041df86b94bff193bb7de5d1ba14");
        assertThat(response.capturedAt()).isEqualTo(NOW);
        assertThat(response.validUntil()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void failsClosedForNoRowAmbiguityInvalidNativeFactsOrRepositoryFailure() {
        when(repository.resolve(TENANT_ID, PERSON_PUBLIC_ID)).thenReturn(List.of());
        assertUnavailable();

        when(repository.resolve(TENANT_ID, PERSON_PUBLIC_ID))
                .thenReturn(List.of(validRow(), validRow()));
        assertUnavailable();

        for (AuthorityRow invalid : List.of(
                new AuthorityRow(null, TENANT_PUBLIC_ID, "ACTIVE", 4L, 93L,
                        PRINCIPAL_PUBLIC_ID, PERSON_PUBLIC_ID, "ACTIVE", "TENANT", 7L, 11L),
                new AuthorityRow(TENANT_ID, null, "ACTIVE", 4L, 93L,
                        PRINCIPAL_PUBLIC_ID, PERSON_PUBLIC_ID, "ACTIVE", "TENANT", 7L, 11L),
                new AuthorityRow(TENANT_ID, TENANT_PUBLIC_ID, "INACTIVE", 4L, 93L,
                        PRINCIPAL_PUBLIC_ID, PERSON_PUBLIC_ID, "ACTIVE", "TENANT", 7L, 11L),
                new AuthorityRow(TENANT_ID, TENANT_PUBLIC_ID, "ACTIVE", -1L, 93L,
                        PRINCIPAL_PUBLIC_ID, PERSON_PUBLIC_ID, "ACTIVE", "TENANT", 7L, 11L),
                new AuthorityRow(TENANT_ID, TENANT_PUBLIC_ID, "ACTIVE", 4L, 0L,
                        PRINCIPAL_PUBLIC_ID, PERSON_PUBLIC_ID, "ACTIVE", "TENANT", 7L, 11L),
                new AuthorityRow(TENANT_ID, TENANT_PUBLIC_ID, "ACTIVE", 4L, 93L,
                        null, PERSON_PUBLIC_ID, "ACTIVE", "TENANT", 7L, 11L),
                new AuthorityRow(TENANT_ID, TENANT_PUBLIC_ID, "ACTIVE", 4L, 93L,
                        PRINCIPAL_PUBLIC_ID, UUID.randomUUID(), "ACTIVE", "TENANT", 7L, 11L),
                new AuthorityRow(TENANT_ID, TENANT_PUBLIC_ID, "ACTIVE", 4L, 93L,
                        PRINCIPAL_PUBLIC_ID, PERSON_PUBLIC_ID, "SUSPENDED", "TENANT", 7L, 11L),
                new AuthorityRow(TENANT_ID, TENANT_PUBLIC_ID, "ACTIVE", 4L, 93L,
                        PRINCIPAL_PUBLIC_ID, PERSON_PUBLIC_ID, "ACTIVE", "PROVIDER", 7L, 11L),
                new AuthorityRow(TENANT_ID, TENANT_PUBLIC_ID, "ACTIVE", 4L, 93L,
                        PRINCIPAL_PUBLIC_ID, PERSON_PUBLIC_ID, "ACTIVE", "TENANT", -1L, 11L),
                new AuthorityRow(TENANT_ID, TENANT_PUBLIC_ID, "ACTIVE", 4L, 93L,
                        PRINCIPAL_PUBLIC_ID, PERSON_PUBLIC_ID, "ACTIVE", "TENANT", 7L, -1L))) {
            when(repository.resolve(TENANT_ID, PERSON_PUBLIC_ID))
                    .thenReturn(List.of(invalid));
            assertUnavailable();
        }

        when(repository.resolve(TENANT_ID, PERSON_PUBLIC_ID))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertUnavailable();
    }

    @Test
    void rejectsInvalidPurposeBeforeAnyNativeAuthorityRead() {
        HrisRecipientAuthorityDtos.ResolveRequest invalid =
                new HrisRecipientAuthorityDtos.ResolveRequest(
                        REQUEST_ID.toString(), TENANT_ID, PERSON_PUBLIC_ID.toString(),
                        "HCM.EMPLOYEE_CHANGE_CREATED_NOTIFICATION");

        assertThatThrownBy(() -> service.resolve(invalid))
                .isInstanceOf(HrisRecipientAuthorityService
                        .RecipientAuthorityUnavailableException.class);
        verifyNoInteractions(repository);
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> service.resolve(request()))
                .isInstanceOf(HrisRecipientAuthorityService
                        .RecipientAuthorityUnavailableException.class)
                .hasMessage("Recipient authority is unavailable.");
    }

    private HrisRecipientAuthorityDtos.ResolveRequest request() {
        return new HrisRecipientAuthorityDtos.ResolveRequest(
                REQUEST_ID.toString(), TENANT_ID, PERSON_PUBLIC_ID.toString(),
                HrisRecipientAuthorityDtos.PURPOSE);
    }

    private AuthorityRow validRow() {
        return new AuthorityRow(
                TENANT_ID,
                TENANT_PUBLIC_ID,
                "ACTIVE",
                4L,
                93L,
                PRINCIPAL_PUBLIC_ID,
                PERSON_PUBLIC_ID,
                "ACTIVE",
                "TENANT",
                7L,
                11L);
    }
}
