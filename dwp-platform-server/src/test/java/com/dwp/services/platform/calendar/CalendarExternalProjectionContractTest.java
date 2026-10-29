package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarExternalProjectionContractTest {

    @Test
    void freezesSourceEvidenceStatesAndReadOnlyCapabilities() {
        assertThat(EnumSetNames.of(CalendarExternalDtos.SourceStatus.values()))
                .containsExactlyInAnyOrder(
                        "HEALTHY", "STALE", "AUTH_REQUIRED", "UNAVAILABLE", "PARTIAL");

        CalendarExternalDtos.ReadOnlyCapabilities capabilities =
                CalendarExternalDtos.ReadOnlyCapabilities.eventDetails();

        assertThat(capabilities.readOnly()).isTrue();
        assertThat(capabilities.canViewDetails()).isTrue();
        assertThat(List.of(
                capabilities.canCreateEvents(), capabilities.canEdit(), capabilities.canDelete(),
                capabilities.canRestore(), capabilities.canRespond(), capabilities.canStar()))
                .containsOnly(false);
    }

    @Test
    void generatesStableIdsWithoutSharingThemAcrossTenantOrUserScopes() {
        UUID source = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID item = UUID.fromString("20000000-0000-0000-0000-000000000002");

        UUID calendar = CalendarExternalIds.calendarId(
                7L, 42L, CalendarExternalDtos.Provider.MICROSOFT_365, source);
        UUID repeated = CalendarExternalIds.calendarId(
                7L, 42L, CalendarExternalDtos.Provider.MICROSOFT_365, source);

        assertThat(repeated).isEqualTo(calendar);
        assertThat(CalendarExternalIds.eventId(calendar, item))
                .isEqualTo(CalendarExternalIds.eventId(repeated, item));
        assertThat(CalendarExternalIds.calendarId(
                8L, 42L, CalendarExternalDtos.Provider.MICROSOFT_365, source))
                .isNotEqualTo(calendar);
        assertThat(CalendarExternalIds.calendarId(
                7L, 43L, CalendarExternalDtos.Provider.MICROSOFT_365, source))
                .isNotEqualTo(calendar);
    }

    @Test
    void projectionCompletenessMustMatchBoundedSafeEvidence() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-28T00:00:00Z");
        CalendarExternalDtos.SourceEvidence partial = new CalendarExternalDtos.SourceEvidence(
                UUID.randomUUID(), CalendarExternalDtos.Provider.MICROSOFT_365,
                CalendarExternalDtos.SourceStatus.PARTIAL, now,
                "MICROSOFT_CALENDAR_RESULT_TRUNCATED", true);

        CalendarExternalDtos.Projection projection = new CalendarExternalDtos.Projection(
                List.of(), List.of(partial), true, true, now);

        assertThat(projection.partial()).isTrue();
        assertThat(projection.truncated()).isTrue();
        assertThatThrownBy(() -> new CalendarExternalDtos.SourceEvidence(
                UUID.randomUUID(), CalendarExternalDtos.Provider.MICROSOFT_365,
                CalendarExternalDtos.SourceStatus.UNAVAILABLE, null,
                "raw provider body: token=secret", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void healthyAndStaleEvidenceRequiresACompletedSynchronization() {
        assertThatThrownBy(() -> new CalendarExternalDtos.SourceEvidence(
                UUID.randomUUID(), CalendarExternalDtos.Provider.MICROSOFT_365,
                CalendarExternalDtos.SourceStatus.HEALTHY, null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalendarExternalDtos.SourceEvidence(
                UUID.randomUUID(), CalendarExternalDtos.Provider.MICROSOFT_365,
                CalendarExternalDtos.SourceStatus.STALE, null,
                "MICROSOFT_CALENDAR_STREAM_STALE", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicProjectionRecordsHaveNoProviderSecretOrTransportFields() {
        Set<String> forbidden = Set.of(
                "token", "cursor", "webhook", "payload", "raw", "body", "credential");

        for (Class<?> type : List.of(
                CalendarExternalDtos.Query.class,
                CalendarExternalDtos.Event.class,
                CalendarExternalDtos.SourceEvidence.class,
                CalendarExternalDtos.Projection.class)) {
            Set<String> fields = Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
            assertThat(fields).noneMatch(name -> forbidden.stream().anyMatch(name::contains));
        }
    }

    @Test
    void googleReadinessIsDisabledWithoutInventingActivationOrSync() {
        CalendarExternalProviderReadiness.ProviderReadiness google =
                CalendarExternalProviderReadiness.googleWorkspace();

        assertThat(google.readiness()).isEqualTo(CalendarExternalDtos.Readiness.DISABLED);
        assertThat(google.minimumReadScopes())
                .containsExactly("https://www.googleapis.com/auth/calendar.events.readonly");
        assertThat(google.incrementalSyncContract())
                .isEqualTo("SYNC_TOKEN_HTTP_410_REQUIRES_FULL_RESYNC");
        assertThat(google.notificationContract())
                .isEqualTo("PUSH_NOTIFICATIONS_NOT_GUARANTEED_RECONCILIATION_REQUIRED");
        assertThat(List.of(
                google.credentialProvisioningImplemented(),
                google.tenantApprovalImplemented(),
                google.synchronizationImplemented()))
                .containsOnly(false);
    }

    @Test
    void queryRejectsUnscopedInvalidRangesAndUnboundedPages() {
        OffsetDateTime start = OffsetDateTime.parse("2026-10-28T00:00:00Z");

        assertThatThrownBy(() -> new CalendarExternalDtos.Query(
                0L, 42L, start, start.plusDays(1), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalendarExternalDtos.Query(
                7L, 42L, start, start, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalendarExternalDtos.Query(
                7L, 42L, start, start.plusDays(1), 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalendarExternalDtos.Query(
                7L, 42L, start, start.plusDays(367), 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class EnumSetNames {
        private EnumSetNames() {
        }

        private static List<String> of(Enum<?>[] values) {
            return Arrays.stream(values).map(Enum::name).toList();
        }
    }
}
