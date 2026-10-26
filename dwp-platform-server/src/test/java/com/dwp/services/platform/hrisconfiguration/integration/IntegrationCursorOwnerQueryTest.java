package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Profile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationCursorOwnerQueryTest {

    private static final long TENANT = 71L;
    private static final long ACTOR = 910L;
    private static final Instant AS_OF = Instant.parse("2026-10-26T01:02:03Z");
    private static final UUID SUBJECT = UUID.fromString(
            "4f2b4fa0-3e26-4f54-9258-ff5ef45c4fc1");
    private static final UUID ITEM = UUID.fromString(
            "4cf5afc4-24b5-4d8f-b875-2a491b74db8e");
    private static final String SECRET =
            "sys008-cursor-secret-that-is-at-least-thirty-two-characters";
    private static final Set<String> AUTHOR_ACTIONS = Set.of(
            IntegrationService.CREATE_CONNECTOR,
            IntegrationService.TEST_CONNECTOR,
            IntegrationService.DRY_RUN_MAPPING,
            IntegrationService.VALIDATE_DEFINITION,
            IntegrationService.SUBMIT_DEFINITION,
            IntegrationService.CREATE_MAPPING,
            IntegrationService.VALIDATE_MAPPING,
            IntegrationService.SUBMIT_MAPPING);
    private static final Set<String> EXECUTOR_ACTIONS = Set.of(
            IntegrationService.CREATE_EXECUTION,
            IntegrationService.APPROVE_DEFINITION,
            IntegrationService.ACTIVATE_DEFINITION,
            IntegrationService.APPROVE_MAPPING,
            IntegrationService.ACTIVATE_MAPPING,
            IntegrationService.RECONCILE_EXECUTION,
            IntegrationService.RETRY_EXECUTION,
            IntegrationService.CANCEL_EXECUTION);

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final IntegrationCursorCodec codec = new IntegrationCursorCodec(SECRET, json);

    @Test
    void authenticatedCursorBindsAllElevenAxesAndRejectsTamperAndCrossTenantReuse() {
        IntegrationCursorCodec.CursorBinding binding = binding();
        String token = codec.encode(binding);
        AuthenticatedCursor authenticated = codec.authenticate(token);

        assertThat(codec.decode(token).lastItemPublicId()).isEqualTo(ITEM.toString());
        assertThatCode(() -> trusted(
                TENANT, ACTOR, Projection.PRIMARY_HRIS_SETTINGS, Map.of(),
                DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul",
                21, 22, 23, authenticated)).doesNotThrowAnyException();

        List<Runnable> mismatches = List.of(
                () -> trusted(TENANT + 1, ACTOR, Projection.PRIMARY_HRIS_SETTINGS,
                        Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul",
                        21, 22, 23, authenticated),
                () -> trusted(TENANT, ACTOR + 1, Projection.PRIMARY_HRIS_SETTINGS,
                        Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul",
                        21, 22, 23, authenticated),
                () -> trusted(TENANT, ACTOR, Projection.PRIMARY_DWP_CONTROL,
                        Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul",
                        21, 22, 23, authenticated),
                () -> trusted(TENANT, ACTOR, Projection.PRIMARY_HRIS_SETTINGS,
                        Map.of("connectorMetadata", "bank"), DEFAULT_SORT,
                        AS_OF, "ko-KR", "Asia/Seoul", 21, 22, 23, authenticated),
                () -> trusted(TENANT, ACTOR, Projection.PRIMARY_HRIS_SETTINGS,
                        Map.of(), "connectorMetadata:ASC", AS_OF,
                        "ko-KR", "Asia/Seoul", 21, 22, 23, authenticated),
                () -> trusted(TENANT, ACTOR, Projection.PRIMARY_HRIS_SETTINGS,
                        Map.of(), DEFAULT_SORT, AS_OF.plusSeconds(1),
                        "ko-KR", "Asia/Seoul", 21, 22, 23, authenticated),
                () -> trusted(TENANT, ACTOR, Projection.PRIMARY_HRIS_SETTINGS,
                        Map.of(), DEFAULT_SORT, AS_OF, "en-US", "Asia/Seoul",
                        21, 22, 23, authenticated),
                () -> trusted(TENANT, ACTOR, Projection.PRIMARY_HRIS_SETTINGS,
                        Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "UTC",
                        21, 22, 23, authenticated),
                () -> trusted(TENANT, ACTOR, Projection.PRIMARY_HRIS_SETTINGS,
                        Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul",
                        20, 22, 23, authenticated),
                () -> trusted(TENANT, ACTOR, Projection.PRIMARY_HRIS_SETTINGS,
                        Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul",
                        21, 20, 23, authenticated),
                () -> trusted(TENANT, ACTOR, Projection.PRIMARY_HRIS_SETTINGS,
                        Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul",
                        21, 22, 20, authenticated));
        assertThat(mismatches).hasSize(CURSOR_BINDINGS.size());
        mismatches.forEach(mismatch -> assertThatThrownBy(mismatch::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all eleven authenticated bindings"));

        String tampered = (token.charAt(0) == 'A' ? "B" : "A") + token.substring(1);
        assertThatThrownBy(() -> codec.authenticate(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownerQueryIntersectsRoleAuthorityWithRepositoryItemEligibility() {
        IntegrationRepository repository = mock(IntegrationRepository.class);
        IntegrationOwnerQueryService owner = new IntegrationOwnerQueryService(repository, codec);
        Set<String> repositoryEligible = Set.of(
                IntegrationService.VALIDATE_DEFINITION,
                IntegrationService.APPROVE_DEFINITION,
                IntegrationService.RETRY_EXECUTION);
        when(repository.ownerPage(any(), isNull(), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> allowedByRole = invocation.getArgument(2, List.class);
            Set<String> intersection = new java.util.HashSet<>(repositoryEligible);
            intersection.retainAll(Set.copyOf(allowedByRole));
            return new IntegrationRepository.OwnerPage(
                    QueryState.COMPLETE, true,
                    List.of(ownerItem(intersection)), "server-only-owner-receipt", null);
        });

        ProjectionResponse authorResponse = owner.query(
                authority(true, false), Projection.PRIMARY_HRIS_SETTINGS,
                Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul", 50, null);
        ProjectionResponse executorResponse = owner.query(
                authority(false, true), Projection.PRIMARY_HRIS_SETTINGS,
                Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul", 50, null);

        assertThat(authorResponse.items()).singleElement().satisfies(item ->
                assertThat(item.availableActions()).containsExactly(
                        IntegrationService.VALIDATE_DEFINITION));
        assertThat(executorResponse.items()).singleElement().satisfies(item ->
                assertThat(item.availableActions()).containsExactlyInAnyOrder(
                        IntegrationService.APPROVE_DEFINITION,
                        IntegrationService.RETRY_EXECUTION));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> actions = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<TrustedOwnerQuery> queries =
                ArgumentCaptor.forClass(TrustedOwnerQuery.class);
        verify(repository, org.mockito.Mockito.times(2))
                .ownerPage(queries.capture(), isNull(), actions.capture());
        verify(repository, org.mockito.Mockito.times(2))
                .bindTenantPrincipal(TENANT, SUBJECT);
        assertThat(queries.getAllValues()).allSatisfy(query -> {
            assertThat(query.tenantId()).isEqualTo(TENANT);
            assertThat(query.actorId()).isEqualTo(ACTOR);
            assertThat(query.asOf()).isEqualTo(AS_OF);
            assertThat(query.locale()).isEqualTo("ko-KR");
            assertThat(query.timeZone()).isEqualTo("Asia/Seoul");
            assertThat(query.scopeRevision()).isEqualTo(21);
            assertThat(query.fieldPolicyRevision()).isEqualTo(22);
            assertThat(query.authorizationRevision()).isEqualTo(23);
        });
        assertThat(actions.getAllValues().get(0))
                .containsExactlyInAnyOrderElementsOf(AUTHOR_ACTIONS);
        assertThat(actions.getAllValues().get(1))
                .containsExactlyInAnyOrderElementsOf(EXECUTOR_ACTIONS);
        assertThat(AUTHOR_ACTIONS).doesNotContainAnyElementsOf(EXECUTOR_ACTIONS);
        assertThat(java.util.stream.Stream.concat(
                AUTHOR_ACTIONS.stream(), EXECUTOR_ACTIONS.stream()).collect(
                        java.util.stream.Collectors.toUnmodifiableSet()))
                .containsExactlyInAnyOrderElementsOf(ACTION_KEYS);
    }

    @Test
    void projectionSerializesOnlyClosedShapeAndKeepsOwnerReceiptServerSide()
            throws Exception {
        IntegrationRepository repository = mock(IntegrationRepository.class);
        when(repository.ownerPage(any(), isNull(), anyList())).thenReturn(
                new IntegrationRepository.OwnerPage(
                        QueryState.COMPLETE, true,
                        List.of(ownerItem(Set.of())), "never-serialized-owner-receipt", null));
        IntegrationOwnerQueryService owner = new IntegrationOwnerQueryService(repository, codec);

        ProjectionResponse response = owner.query(
                authority(true, false), Projection.PRIMARY_DWP_CONTROL,
                Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul", 50, null);
        var tree = json.readTree(json.writeValueAsBytes(response));

        assertThat(tree.propertyStream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("projection", "state", "items");
        assertThat(tree.toString()).doesNotContain(
                "tenant", "ownerReceipt", "never-serialized-owner-receipt");
    }

    private static TrustedOwnerQuery trusted(
            long tenant,
            long actor,
            Projection projection,
            Map<String, String> filters,
            String sort,
            Instant asOf,
            String locale,
            String timeZone,
            long scopeRevision,
            long fieldRevision,
            long authorizationRevision,
            AuthenticatedCursor cursor) {
        return new TrustedOwnerQuery(
                tenant, actor, projection, filters, sort, asOf, locale, timeZone,
                scopeRevision, fieldRevision, authorizationRevision, 50, cursor);
    }

    private static IntegrationCursorCodec.CursorBinding binding() {
        return new IntegrationCursorCodec.CursorBinding(
                TENANT, ACTOR, Projection.PRIMARY_HRIS_SETTINGS,
                Map.of(), DEFAULT_SORT, AS_OF, "ko-KR", "Asia/Seoul",
                21, 22, 23, ITEM.toString());
    }

    private static Authority authority(boolean author, boolean executor) {
        Set<Profile> profiles = new java.util.HashSet<>();
        profiles.add(new Profile(
                "CONFIG.HRIS_POLICY:VIEW", "HRIS_CONFIG_AUTHOR",
                "HRIS_DUTY_CONFIGURATION_WORKBENCH_READER"));
        if (author) {
            profiles.add(new Profile(
                    "CONFIG.HRIS_INTEGRATION:UPDATE", "HRIS_INTEGRATION_AUTHOR",
                    "HRIS_DUTY_INTEGRATION_AUTHOR"));
        }
        if (executor) {
            profiles.add(new Profile(
                    "ACTION.HRIS_INTEGRATION_CONTROL_PLANE:EXECUTE",
                    "HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR",
                    "HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR"));
        }
        return new Authority(
                TENANT, ACTOR, SUBJECT, profiles,
                "route.hcm.configuration.settings-integrations.page",
                "psr-" + "a".repeat(64), OffsetDateTime.now().plusHours(1),
                "b".repeat(64), 21, 22, 23,
                "psc-" + "c".repeat(64), "tenant:71:hris-integration",
                UUID.fromString("742fcf44-7837-4bc5-91e8-47a3237a22f8"),
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE,
                null, null, null, "TENANT", null, Set.of(), Set.of());
    }

    private static OwnerItem ownerItem(Set<String> actions) {
        EnumMap<FieldGroup, Object> fields = new EnumMap<>(FieldGroup.class);
        fields.put(FieldGroup.CONNECTOR_METADATA, Map.of("connectorKey", "bank"));
        fields.put(FieldGroup.POLICY_VERSION, Map.of("definitionVersion", 1));
        fields.put(FieldGroup.EXECUTION_POLICY, Map.of("direction", "OUTBOUND"));
        fields.put(FieldGroup.EFFECTIVE_STATUS, Map.of("definitionState", "ACTIVE"));
        return new OwnerItem(ITEM, 9, fields, actions);
    }
}
