package com.dwp.services.platform.productivity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.dwp.services.platform.productivity.ProductivityTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductivityCalendarProjectionFacadeTest {

    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 42L;
    private static final Instant FROM = Instant.parse("2026-10-28T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-04T00:00:00Z");
    private static final Instant LAST_SUCCESS = Instant.parse("2026-10-28T01:00:00Z");

    private ProductivityRepository productivity;
    private ProductivityCalendarProjectionRepository projection;
    private ProductivityCrypto crypto;
    private ProductivityCalendarProjectionFacade facade;
    private UUID connectorId;
    private UUID subjectId;

    @BeforeEach
    void setUp() {
        productivity = mock(ProductivityRepository.class);
        projection = mock(ProductivityCalendarProjectionRepository.class);
        crypto = mock(ProductivityCrypto.class);
        facade = new ProductivityCalendarProjectionFacade(productivity, projection, crypto);
        connectorId = UUID.randomUUID();
        subjectId = UUID.randomUUID();
        when(crypto.available()).thenReturn(true);
        when(productivity.connectors(TENANT_ID)).thenReturn(List.of(connector(ConnectorHealth.HEALTHY)));
        when(productivity.subject(TENANT_ID, connectorId, USER_ID))
                .thenReturn(Optional.of(subject(ConsentState.CONNECTED)));
        when(productivity.stream(TENANT_ID, subjectId, ResourceKind.CALENDAR))
                .thenReturn(Optional.of(stream(StreamState.READY, LAST_SUCCESS)));
        when(crypto.decrypt(anyString(), anyString())).thenAnswer(invocation -> {
            String encrypted = invocation.getArgument(0);
            return encrypted.startsWith("encrypted-title-")
                    ? "Planning " + encrypted.substring("encrypted-title-".length())
                    : "https://outlook.office.com/calendar/item/"
                            + encrypted.substring("encrypted-url-".length());
        });
    }

    @Test
    void exposesHealthyDecryptedProjectionThroughTheProductivityCryptoBoundary() {
        ProductivityRepository.ItemRecord item = item(1, TENANT_ID, USER_ID, connectorId,
                ResourceKind.CALENDAR, false, FROM.plusSeconds(3600), FROM.plusSeconds(7200));
        when(projection.calendarItems(TENANT_ID, USER_ID, connectorId, FROM, TO, 101))
                .thenReturn(List.of(item));

        ProductivityCalendarProjectionFacade.Snapshot result =
                facade.project(TENANT_ID, USER_ID, FROM, TO, 100);

        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.title()).isEqualTo("Planning 1");
            assertThat(event.sourceUrl())
                    .isEqualTo("https://outlook.office.com/calendar/item/1");
            assertThat(event.startsAt()).isEqualTo(item.occurredAt());
            assertThat(event.endsAt()).isEqualTo(item.endsAt());
        });
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.status())
                    .isEqualTo(ProductivityCalendarProjectionFacade.Status.HEALTHY);
            assertThat(source.lastSuccessfulSyncAt()).isEqualTo(LAST_SUCCESS);
            assertThat(source.safeReasonCode()).isNull();
        });
        assertThat(result.partial()).isFalse();
        verify(crypto).decrypt(
                "encrypted-title-1",
                "item:7:42:" + connectorId + ":CALENDAR:source-hash-1:title");
        verify(crypto).decrypt(
                "encrypted-url-1",
                "item:7:42:" + connectorId + ":CALENDAR:source-hash-1:url");
    }

    @ParameterizedTest
    @EnumSource(value = ConsentState.class, names = {"REVOKED", "REAUTHORIZATION_REQUIRED"})
    void revokedOrReauthorizationRequiredConnectorFailsClosed(ConsentState consentState) {
        when(productivity.subject(TENANT_ID, connectorId, USER_ID))
                .thenReturn(Optional.of(subject(consentState)));

        ProductivityCalendarProjectionFacade.Snapshot result =
                facade.project(TENANT_ID, USER_ID, FROM, TO, 100);

        assertThat(result.events()).isEmpty();
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.status())
                    .isEqualTo(ProductivityCalendarProjectionFacade.Status.AUTH_REQUIRED);
            assertThat(source.safeReasonCode())
                    .isEqualTo("MICROSOFT_CALENDAR_REAUTHORIZATION_REQUIRED");
        });
        verify(projection, never()).calendarItems(
                TENANT_ID, USER_ID, connectorId, FROM, TO, 101);
        verify(crypto, never()).decrypt(anyString(), anyString());
    }

    @Test
    void staleSourceKeepsBoundedCachedEventsWithLastSuccessEvidence() {
        when(productivity.stream(TENANT_ID, subjectId, ResourceKind.CALENDAR))
                .thenReturn(Optional.of(stream(StreamState.STALE, LAST_SUCCESS)));
        when(projection.calendarItems(TENANT_ID, USER_ID, connectorId, FROM, TO, 101))
                .thenReturn(List.of(item(1, TENANT_ID, USER_ID, connectorId,
                        ResourceKind.CALENDAR, false, FROM.plusSeconds(60), FROM.plusSeconds(120))));

        ProductivityCalendarProjectionFacade.Snapshot result =
                facade.project(TENANT_ID, USER_ID, FROM, TO, 100);

        assertThat(result.events()).hasSize(1);
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.status())
                    .isEqualTo(ProductivityCalendarProjectionFacade.Status.STALE);
            assertThat(source.lastSuccessfulSyncAt()).isEqualTo(LAST_SUCCESS);
            assertThat(source.safeReasonCode()).isEqualTo("MICROSOFT_CALENDAR_STREAM_STALE");
        });
        assertThat(result.partial()).isTrue();
    }

    @Test
    void connectorAggregateSuccessDoesNotStandInForThisUsersSyncEvidence() {
        when(productivity.stream(TENANT_ID, subjectId, ResourceKind.CALENDAR))
                .thenReturn(Optional.of(stream(StreamState.READY, null)));
        when(productivity.subject(TENANT_ID, connectorId, USER_ID))
                .thenReturn(Optional.of(subject(ConsentState.CONNECTED, null)));

        ProductivityCalendarProjectionFacade.Snapshot result =
                facade.project(TENANT_ID, USER_ID, FROM, TO, 100);

        assertThat(result.events()).isEmpty();
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.status())
                    .isEqualTo(ProductivityCalendarProjectionFacade.Status.UNAVAILABLE);
            assertThat(source.safeReasonCode())
                    .isEqualTo("MICROSOFT_CALENDAR_NO_SUCCESSFUL_SYNC");
        });
        verify(projection, never()).calendarItems(
                TENANT_ID, USER_ID, connectorId, FROM, TO, 101);
    }

    @ParameterizedTest
    @MethodSource("unsafeSourceUrls")
    void removesUnsafeDecryptedSourceUrlsAndMarksTheSourcePartial(String unsafeSourceUrl) {
        when(projection.calendarItems(TENANT_ID, USER_ID, connectorId, FROM, TO, 101))
                .thenReturn(List.of(item(1, TENANT_ID, USER_ID, connectorId,
                        ResourceKind.CALENDAR, false, FROM.plusSeconds(60), FROM.plusSeconds(120))));
        when(crypto.decrypt(
                "encrypted-url-1",
                "item:7:42:" + connectorId + ":CALENDAR:source-hash-1:url"))
                .thenReturn(unsafeSourceUrl);

        ProductivityCalendarProjectionFacade.Snapshot result =
                facade.project(TENANT_ID, USER_ID, FROM, TO, 100);

        assertThat(result.events()).singleElement().satisfies(event ->
                assertThat(event.sourceUrl()).isNull());
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.status())
                    .isEqualTo(ProductivityCalendarProjectionFacade.Status.PARTIAL);
            assertThat(source.safeReasonCode())
                    .isEqualTo("MICROSOFT_CALENDAR_UNSAFE_SOURCE_URL_REMOVED");
        });
        assertThat(result.partial()).isTrue();
    }

    @Test
    void filtersOutOfScopeCancelledOrInvalidRowsAgainBeforeDecryption() {
        ProductivityRepository.ItemRecord valid = item(1, TENANT_ID, USER_ID, connectorId,
                ResourceKind.CALENDAR, false, FROM.plusSeconds(10), FROM.plusSeconds(20));
        ProductivityRepository.ItemRecord wrongTenant = item(2, 8L, USER_ID, connectorId,
                ResourceKind.CALENDAR, false, FROM.plusSeconds(10), FROM.plusSeconds(20));
        ProductivityRepository.ItemRecord cancelled = item(3, TENANT_ID, USER_ID, connectorId,
                ResourceKind.CALENDAR, true, FROM.plusSeconds(10), FROM.plusSeconds(20));
        ProductivityRepository.ItemRecord outside = item(4, TENANT_ID, USER_ID, connectorId,
                ResourceKind.CALENDAR, false, TO, TO.plusSeconds(20));
        when(projection.calendarItems(TENANT_ID, USER_ID, connectorId, FROM, TO, 101))
                .thenReturn(List.of(valid, wrongTenant, cancelled, outside));

        ProductivityCalendarProjectionFacade.Snapshot result =
                facade.project(TENANT_ID, USER_ID, FROM, TO, 100);

        assertThat(result.events()).extracting(ProductivityCalendarProjectionFacade.ProjectedEvent::itemId)
                .containsExactly(valid.itemId());
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.status())
                    .isEqualTo(ProductivityCalendarProjectionFacade.Status.PARTIAL);
            assertThat(source.safeReasonCode())
                    .isEqualTo("MICROSOFT_CALENDAR_INVALID_ITEM_SKIPPED");
            assertThat(source.truncated()).isFalse();
        });
        verify(crypto, never()).decrypt(
                "encrypted-title-2",
                "item:8:42:" + connectorId + ":CALENDAR:source-hash-2:title");
    }

    @Test
    void fetchesOneLookaheadAndMarksMoreThanOneHundredRowsPartial() {
        List<ProductivityRepository.ItemRecord> items = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> item(
                        index, TENANT_ID, USER_ID, connectorId, ResourceKind.CALENDAR, false,
                        FROM.plusSeconds(index * 60L), FROM.plusSeconds(index * 60L + 30L)))
                .toList();
        when(projection.calendarItems(TENANT_ID, USER_ID, connectorId, FROM, TO, 101))
                .thenReturn(items);

        ProductivityCalendarProjectionFacade.Snapshot result =
                facade.project(TENANT_ID, USER_ID, FROM, TO, 100);

        assertThat(result.events()).hasSize(100);
        assertThat(result.events()).extracting(ProductivityCalendarProjectionFacade.ProjectedEvent::itemId)
                .doesNotHaveDuplicates();
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.status())
                    .isEqualTo(ProductivityCalendarProjectionFacade.Status.PARTIAL);
            assertThat(source.safeReasonCode())
                    .isEqualTo("MICROSOFT_CALENDAR_RESULT_TRUNCATED");
            assertThat(source.truncated()).isTrue();
        });
        assertThat(result.partial()).isTrue();
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void rejectsRangesBeyondTheGovernedWindowBeforeDatabaseAccess() {
        assertThatThrownBy(() -> facade.project(
                TENANT_ID, USER_ID, FROM, FROM.plusSeconds(367L * 24 * 60 * 60), 100))
                .isInstanceOf(IllegalArgumentException.class);
        verify(productivity, never()).connectors(TENANT_ID);
    }

    private ProductivityRepository.ConnectorRecord connector(ConnectorHealth health) {
        return connector(health, LAST_SUCCESS);
    }

    private ProductivityRepository.ConnectorRecord connector(
            ConnectorHealth health,
            Instant lastSuccess) {
        return new ProductivityRepository.ConnectorRecord(
                connectorId, TENANT_ID, "MICROSOFT_365", "Microsoft 365",
                ProviderType.MICROSOFT_GRAPH, AuthMode.DELEGATED,
                "organizations", "11111111-1111-1111-1111-111111111111",
                "env:DWP_MS_GRAPH_CLIENT_SECRET", "https://localhost/callback",
                List.of("openid", "offline_access", "Calendars.Read"),
                List.of("CALENDAR_EVENTS", "DELTA_SYNC"), ConnectorLifecycle.ACTIVE,
                health, PolicyState.APPROVED, null, LAST_SUCCESS, lastSuccess, 0, 1);
    }

    private ProductivityRepository.SubjectRecord subject(ConsentState consent) {
        return subject(consent, LAST_SUCCESS);
    }

    private ProductivityRepository.SubjectRecord subject(
            ConsentState consent,
            Instant lastSuccess) {
        return new ProductivityRepository.SubjectRecord(
                subjectId, TENANT_ID, connectorId, USER_ID, "subject-hash", "encrypted-token",
                List.of("Calendars.Read"), consent, TO, lastSuccess, null, 1);
    }

    private ProductivityRepository.StreamRecord stream(StreamState state, Instant lastSuccess) {
        return new ProductivityRepository.StreamRecord(
                UUID.randomUUID(), TENANT_ID, subjectId, ResourceKind.CALENDAR,
                "encrypted-cursor", "cursor-fingerprint", FROM.minusSeconds(60), TO.plusSeconds(60),
                state, LAST_SUCCESS, lastSuccess, null, 1);
    }

    private static ProductivityRepository.ItemRecord item(
            int index,
            long tenantId,
            long userId,
            UUID connectorId,
            ResourceKind kind,
            boolean cancelled,
            Instant startsAt,
            Instant endsAt) {
        return new ProductivityRepository.ItemRecord(
                new UUID(0L, index), tenantId, userId, connectorId, kind,
                "source-hash-" + index, "encrypted-title-" + index,
                "encrypted-url-" + index, startsAt, endsAt, "normal", null, cancelled,
                "CONFIDENTIAL", "permission-hash", "version-" + index);
    }

    private static Stream<String> unsafeSourceUrls() {
        return Stream.of(
                "javascript:alert(1)",
                "data:text/html,calendar",
                "http://outlook.office.com/calendar/item/1",
                "https://user@outlook.office.com/calendar/item/1",
                "https://example.com/calendar/item/1",
                "https://outlook.office.com.evil.example/calendar/item/1",
                "https://outlook.office.com:444/calendar/item/1",
                "https://outlook.office.com/" + "x".repeat(2_049));
    }
}
