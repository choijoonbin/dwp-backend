package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationModels.InboxView;
import com.dwp.services.notification.domain.NotificationModels.NotificationReason;
import com.dwp.services.notification.domain.NotificationQueryRepository.InboxFilters;
import com.dwp.services.notification.domain.NotificationQueryRepository.InboxRow;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationQueryRepositoryTest {
    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(42, 900018L, Set.of(), Set.of(), false, "dwp-gateway");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final NotificationQueryRepository repository =
            new NotificationQueryRepository(jdbc, new ObjectMapper());

    @ParameterizedTest
    @MethodSource("knownReasons")
    @SuppressWarnings("unchecked")
    void canonicalAndAliasFiltersUseTheSameCodesAsDisplay(
            String raw, String canonical, List<String> aliases) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("reason_code")).thenReturn(raw);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<InboxRow> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });

        var result = repository.inbox(ACTOR, InboxView.ALL, filters(null, raw), 30, null);

        assertThat(result.getFirst().item().reason().kind()).isEqualTo(canonical);
        assertThat(result.getFirst().item().reason().detail()).isEqualTo(raw);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("UPPER(user_notification.reason_code) IN (:reasonCodes)");
        assertThat(params.getValue().getValue("reasonCodes")).isEqualTo(aliases);
        assertThat(params.getValue().getValue("tenantId")).isEqualTo(42L);
        assertThat(params.getValue().getValue("userId")).isEqualTo(900018L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"OWNER", "ASSIGNEE", "DIRECT_EXTRA", "UNKNOWN", "", " ", "private-reason-42"})
    @SuppressWarnings("unchecked")
    void unknownAndNullReasonsHaveNeutralInboxAndDetailWithoutRawCode(String raw) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("reason_code")).thenReturn(raw);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });

        var inboxReason = repository.inbox(ACTOR, InboxView.ALL, filters(null, null), 30, null)
                .getFirst().item().reason();
        var detail = repository.detail(ACTOR, java.util.UUID.randomUUID());

        assertThat(inboxReason).isEqualTo(new NotificationReason("UNKNOWN", "Reason unavailable", null));
        assertThat(detail.item().reason()).isEqualTo(inboxReason);
        assertThat(detail.reasonExplanation())
                .isEqualTo("The reason for receiving this notification is unavailable.")
                .doesNotContain("directly");
    }

    @Test
    void unknownIsAnAdditiveStringWireValueWithoutChangingTheDtoShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        NotificationReason reason = new NotificationReason("UNKNOWN", "Reason unavailable", null);

        assertThat(NotificationReason.class.getDeclaredMethod("kind").getReturnType()).isEqualTo(String.class);
        assertThat(mapper.valueToTree(reason).path("kind").isTextual()).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(reason), NotificationReason.class))
                .isEqualTo(reason);
    }

    @ParameterizedTest
    @CsvSource({
            "'  Human actor  ',Human actor",
            "'user:900018',",
            "'urn:dwp:meetings',",
            "'A72E7930-7D2B-4D9E-86C4-45DCEB42F331',"
    })
    void exposesOnlyHumanReadableActorLabels(String raw, String expected) {
        assertThat(NotificationQueryRepository.displayActorLabel(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "ASSIGNEE", "DIRECT_EXTRA", "%", "DIRECT' OR 1=1 --"})
    @SuppressWarnings("unchecked")
    void unknownFiltersRemainExactParametersAndNeverExpandToDirect(String reason) {
        repository.inbox(ACTOR, InboxView.ALL, filters(null, reason), 30, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("user_notification.reason_code = :reason")
                .doesNotContain(":reasonCodes", reason);
        assertThat(params.getValue().getValue("reason")).isEqualTo(reason);
        assertThat(params.getValue().hasValue("reasonCodes")).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"ALL", "READ", "UNREAD"})
    @SuppressWarnings("unchecked")
    void supportedReadStatesRetainTheirExactPredicates(String readState) {
        repository.inbox(ACTOR, InboxView.ALL, filters(readState, null), 30, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        if ("READ".equals(readState)) {
            assertThat(sql.getValue()).contains("AND user_notification.read_at IS NOT NULL");
        } else if ("UNREAD".equals(readState)) {
            assertThat(sql.getValue()).contains("AND user_notification.read_at IS NULL");
        } else {
            assertThat(sql.getValue()).doesNotContain("AND user_notification.read_at");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "unread", "all", "READ,UNREAD", "UNREDA", "", " ", " READ"})
    void invalidReadStateFailsBeforeAnyQuery(String readState) {
        assertThatThrownBy(() -> repository.inbox(
                ACTOR, InboxView.ALL, filters(readState, null), 30, null))
                .isInstanceOfSatisfying(NotificationException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(NotificationErrorCode.INVALID_INPUT);
                    assertThat(exception.errorCode().status().value()).isEqualTo(400);
                });
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void prioritizedAttentionUsesOnlyTheMaterializedRecipientDecision() {
        InboxFilters filters = new InboxFilters(
                null, null, null, null, null, "PRIORITIZE",
                List.of(), List.of(), null, null);

        repository.inbox(ACTOR, InboxView.ALL, filters, 30, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("user_notification.attention_effect = :attentionEffect")
                .doesNotContain("actor_ref = :attentionEffect");
        assertThat(params.getValue().getValue("attentionEffect")).isEqualTo("PRIORITIZE");
    }

    @Test
    void invalidAttentionEffectFailsBeforeAnyQuery() {
        assertThatThrownBy(() -> new InboxFilters(
                null, null, null, null, null, "VIP_ACTOR",
                List.of(), List.of(), null, null))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(NotificationErrorCode.INVALID_INPUT));
        verifyNoInteractions(jdbc);
    }

    @Test
    void inboxTotalReusesTheExactTenantUserAndFacetPredicates() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(7L);
        InboxFilters filters = new InboxFilters(
                null, "messaging", null, "UNREAD", null, "PRIORITIZE",
                List.of("MENTION"), List.of(), null, null);

        assertThat(repository.inboxTotal(ACTOR, InboxView.ALL, filters)).isEqualTo(7L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(sql.capture(), params.capture(), eq(Long.class));
        assertThat(sql.getValue())
                .contains("COUNT(*)")
                .contains("user_notification.tenant_id = :tenantId")
                .contains("user_notification.user_id = :userId")
                .contains("type.owner_app_key = :appKey")
                .contains("user_notification.read_at IS NULL")
                .contains("user_notification.attention_effect = :attentionEffect")
                .contains("UPPER(user_notification.reason_code) IN (:includedReasonCodes)");
        assertThat(params.getValue().getValue("tenantId")).isEqualTo(42L);
        assertThat(params.getValue().getValue("userId")).isEqualTo(900018L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void includedTypesUseCanonicalBoundedOrReasonAliases() {
        InboxFilters filters = new InboxFilters(
                null, null, null, null, null,
                List.of("ASSIGNED", "DIRECT"), List.of(), null, null);

        repository.inbox(ACTOR, InboxView.ALL, filters, 30, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("user_notification.tenant_id = :tenantId")
                .contains("user_notification.user_id = :userId")
                .contains("UPPER(user_notification.reason_code) IN (:includedReasonCodes)");
        assertThat((List<String>) params.getValue().getValue("includedReasonCodes"))
                .containsExactly("DIRECT", "DIRECT_RECIPIENT", "ROLE");
    }

    @Test
    void invalidDuplicateOrOversizedIncludedTypesFailBeforeQuery() {
        assertThatThrownBy(() -> new InboxFilters(
                null, null, null, null, null,
                List.of("DIRECT", "DIRECT"), List.of(), null, null))
                .isInstanceOf(NotificationException.class);
        assertThatThrownBy(() -> new InboxFilters(
                null, null, null, null, null,
                List.of("UNKNOWN"), List.of(), null, null))
                .isInstanceOf(NotificationException.class);
        assertThatThrownBy(() -> new InboxFilters(
                null, null, null, null, null,
                List.of("DIRECT", "MENTION", "ASSIGNED", "SUBSCRIPTION", "MANDATORY_POLICY", "DIRECT"),
                List.of(), null, null))
                .isInstanceOf(NotificationException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void contextFilterMatchesOnlyExactRecipientOwnedReferences() {
        InboxFilters filters = new InboxFilters(
                null, null, null, null, null,
                List.of(),
                List.of(new NotificationQueryRepository.InboxContextFilter(
                        "THREAD", "conversation:42")), null, null);

        repository.inbox(ACTOR, InboxView.ALL, filters, 30, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("notification.thread_key = :contextKey0")
                .contains("context.tenant_id = user_notification.tenant_id")
                .contains("context.user_id = user_notification.user_id")
                .contains("context.kind IN ('CONVERSATION', 'THREAD', 'CHANNEL')")
                .doesNotContain("LIKE :contextKey0");
        assertThat(params.getValue().getValue("contextKey0")).isEqualTo("conversation:42");
        assertThat(params.getValue().getValue("contextKeyHash0"))
                .isEqualTo(NotificationAttentionScope.sha256("conversation:42"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void contextFiltersUseOrWithinKindAndAndAcrossKinds() {
        InboxFilters filters = new InboxFilters(
                null, null, null, null, null,
                List.of(),
                List.of(
                        new NotificationQueryRepository.InboxContextFilter("RESOURCE", "project:renewal"),
                        new NotificationQueryRepository.InboxContextFilter("ACTOR", "user:84"),
                        new NotificationQueryRepository.InboxContextFilter("ACTOR", "user:42")),
                null, null);

        repository.inbox(ACTOR, InboxView.ALL, filters, 30, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("user_notification.actor_ref = :contextKey0")
                .contains(" OR\n  ((user_notification.actor_ref = :contextKey1)")
                .contains(":contextKey2 IN (user_notification.subject_ref, user_notification.target_ref)")
                .doesNotContain("LIKE :contextKey");
        assertThat(params.getValue().getValue("contextKey0")).isEqualTo("user:42");
        assertThat(params.getValue().getValue("contextKey1")).isEqualTo("user:84");
        assertThat(params.getValue().getValue("contextKey2")).isEqualTo("project:renewal");
    }

    @Test
    void incompleteOrNonCanonicalContextFilterFailsBeforeQuery() {
        assertThatThrownBy(() -> repository.inbox(
                ACTOR,
                InboxView.ALL,
                new InboxFilters(
                        null, null, null, null, null,
                        List.of(),
                        List.of(new NotificationQueryRepository.InboxContextFilter(
                                "THREAD", " conversation:42")), null, null),
                30,
                null))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.INVALID_INPUT));
        verifyNoInteractions(jdbc);
    }

    @Test
    void duplicateOrOversizedContextFiltersFailBeforeQuery() {
        var duplicate = new NotificationQueryRepository.InboxContextFilter("ACTOR", "user:42");
        assertThatThrownBy(() -> new InboxFilters(
                null, null, null, null, null, List.of(), List.of(duplicate, duplicate), null, null))
                .isInstanceOf(NotificationException.class);
        assertThatThrownBy(() -> new InboxFilters(
                null, null, null, null, null,
                List.of(),
                java.util.stream.IntStream.range(0, 6)
                        .mapToObj(index -> new NotificationQueryRepository.InboxContextFilter(
                                "THREAD", "thread:" + index))
                        .toList(),
                null, null))
                .isInstanceOf(NotificationException.class);
        verifyNoInteractions(jdbc);
    }

    private InboxFilters filters(String readState, String reason) {
        return new InboxFilters(
                null, null, null, readState, reason, List.of(), List.of(), null, null);
    }

    private static Stream<Arguments> knownReasons() {
        return Stream.of(
                List.of("DIRECT", "DIRECT_RECIPIENT"),
                List.of("MENTION", "MENTIONED"),
                List.of("ROLE"),
                List.of("ORGANIZATION", "ORG"),
                List.of("SUBSCRIPTION", "SUBSCRIBED"),
                List.of("MANDATORY_POLICY", "MANDATORY"))
                .flatMap(aliases -> aliases.stream().flatMap(alias -> Stream.of(
                        Arguments.of(alias, aliases.getFirst(), aliases),
                        Arguments.of(alias.toLowerCase(Locale.ROOT), aliases.getFirst(), aliases))));
    }
}
