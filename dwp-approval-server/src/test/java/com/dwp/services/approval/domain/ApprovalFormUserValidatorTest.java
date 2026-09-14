package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.field;
import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.group;
import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.schema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Person;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Result;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovalFormUserValidatorTest {

    private static final UUID PERSON = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final OffsetDateTime UNTIL = OffsetDateTime.parse("2026-09-14T02:05:00Z");
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-14T02:00:00Z"), ZoneOffset.UTC);
    private final ApprovalFormSchemaV2Compiler compiler = new ApprovalFormSchemaV2Compiler();
    private final ApprovalFormSchemaV2 compiled = compiler.compile(schema(field("reviewer", "USER")));
    private final FormBinding form = binding(compiled.sha256());
    private final Authority authority = evidence(form, ApprovalFormUserDirectory.SOURCE_POLICY, "revision-1", UNTIL);
    private final ApprovalFormUserDirectory directory = mock(ApprovalFormUserDirectory.class);

    @Test
    void validatesCanonicalBindingAndReturnsTheOriginalNormalizedStringNotDisplayName() {
        when(directory.resolve(authority, List.of(PERSON))).thenReturn(new Result(authority,
                List.of(person(PERSON, 10L, "TENANT", "ACTIVE", 101L))));
        var result = validator(ignored -> authority).validate(form, compiled,
                Map.of("reviewer", PERSON.toString()), true);
        assertThat(result.payload()).containsExactly(Map.entry("reviewer", PERSON.toString()));
        verify(directory).resolve(authority, List.of(PERSON));
        verify(directory, never()).search(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void validIncompleteDraftNeedsSourceAuthorityButDoesNotResolveMissingPeople() {
        assertThat(validator(ignored -> authority).validate(form, compiled, Map.of(), false).payload()).isEmpty();
        verify(directory, never()).resolve(any(), anyList());
        assertCode(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> validator(ignored -> null).validate(form, compiled, Map.of(), false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UNREGISTERED_SOURCE", "APPROVAL_FORM_TENANT_PEOPLE_V2"})
    void createPermissionOrAnUnregisteredSourceCannotStandInForExactSourceAuthority(String policy) {
        var wrong = evidence(form, policy, "revision-1", UNTIL);
        assertCode(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> validator(ignored -> wrong).validate(form, compiled, Map.of("reviewer", PERSON.toString()), false));
        verify(directory, never()).resolve(any(), anyList());
    }

    @Test
    void rejectsWrongFormSnapshotBeforeDirectoryAccess() {
        assertCode(ErrorCode.RESOURCE_CONFLICT,
                () -> validator(ignored -> authority).validate(binding("0".repeat(64)), compiled, Map.of(), false));
        var crossActor = new FormBinding(10L, 99L, form.formId(), form.formVersionId(), compiled.sha256());
        assertCode(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> validator(ignored -> authority).validate(crossActor, compiled, Map.of(), false));
        verify(directory, never()).resolve(any(), anyList());
    }

    @Test
    void expiredAuthorityAndMissingScopeOrRouteFailClosed() {
        for (Authority bad : List.of(evidence(form, ApprovalFormUserDirectory.SOURCE_POLICY, "revision-1",
                OffsetDateTime.now(clock)), new Authority(form, ApprovalFormUserDirectory.SOURCE_POLICY,
                "approval-context", "", "revision-1", "lookup-route", UNTIL),
                new Authority(form, ApprovalFormUserDirectory.SOURCE_POLICY,
                        "approval-context", "opaque-scope", "revision-1", "", UNTIL))) {
            assertCode(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    () -> validator(ignored -> bad).validate(form, compiled, Map.of(), false));
        }
        verify(directory, never()).resolve(any(), anyList());
    }

    @Test
    void revocationDuringResolutionPreventsReturningAWriteReadyPayload() {
        var revoked = new AtomicReference<Boolean>(false);
        when(directory.resolve(authority, List.of(PERSON))).thenAnswer(invocation -> {
            revoked.set(true);
            return new Result(authority, List.of(person(PERSON, 10L, "TENANT", "ACTIVE", 101L)));
        });
        assertCode(ErrorCode.FORBIDDEN, () -> validator(ignored -> {
            if (revoked.get()) throw new BaseException(ErrorCode.FORBIDDEN);
            return authority;
        }).validate(form, compiled, Map.of("reviewer", PERSON.toString()), false));
    }

    @Test
    void changedDecisionRevisionRequiresExplicitRefreshEvenWhenTheNewDecisionAllowsAccess() {
        var current = new AtomicReference<>(authority);
        when(directory.resolve(authority, List.of(PERSON))).thenAnswer(invocation -> {
            current.set(evidence(form, ApprovalFormUserDirectory.SOURCE_POLICY, "revision-2", UNTIL));
            return new Result(authority, List.of(person(PERSON, 10L, "TENANT", "ACTIVE", 101L)));
        });
        assertCode(ErrorCode.DECISION_REVISION_CONFLICT,
                () -> validator(ignored -> current.get()).validate(form, compiled, Map.of("reviewer", PERSON.toString()), false));
    }

    @Test
    void sourceCannotEchoAnotherScopeRevisionOrFormAsIfItWereCurrent() {
        var different = new Authority(authority.form(), authority.sourcePolicyKey(), authority.contextKey(),
                "another-opaque-scope", authority.decisionRevision(), authority.routeContractKey(), authority.validUntil());
        when(directory.resolve(authority, List.of(PERSON))).thenReturn(new Result(different,
                List.of(person(PERSON, 10L, "TENANT", "ACTIVE", 101L))));
        assertCode(ErrorCode.DECISION_REVISION_CONFLICT,
                () -> validator(ignored -> authority).validate(form, compiled, Map.of("reviewer", PERSON.toString()), false));
    }

    @Test
    void missingExtraAndDuplicateResultsNeverPartiallyAuthorizeThePayload() {
        var other = UUID.fromString("87654321-4321-4321-4321-cba987654321");
        for (List<Person> people : List.of(List.<Person>of(),
                List.of(person(other, 10L, "TENANT", "ACTIVE", 102L)),
                List.of(person(PERSON, 10L, "TENANT", "ACTIVE", 101L), person(PERSON, 10L, "TENANT", "ACTIVE", 101L)))) {
            when(directory.resolve(authority, List.of(PERSON))).thenReturn(new Result(authority, people));
            assertCode(ErrorCode.FORBIDDEN,
                    () -> validator(ignored -> authority).validate(form, compiled, Map.of("reviewer", PERSON.toString()), false));
        }
    }

    @Test
    void rejectsCrossTenantProviderInactiveAndInvalidSubjectMappings() {
        for (Person bad : List.of(person(PERSON, 11L, "TENANT", "ACTIVE", 101L),
                person(PERSON, 10L, "PROVIDER", "ACTIVE", 101L), person(PERSON, 10L, "TENANT", "INACTIVE", 101L),
                person(PERSON, 10L, "TENANT", "INVITED", 101L), person(PERSON, 10L, "TENANT", "ACTIVE", 0L),
                person(PERSON, null, "TENANT", "ACTIVE", 101L))) {
            when(directory.resolve(authority, List.of(PERSON))).thenReturn(new Result(authority, List.of(bad)));
            assertCode(ErrorCode.FORBIDDEN,
                    () -> validator(ignored -> authority).validate(form, compiled, Map.of("reviewer", PERSON.toString()), false));
        }
    }

    @Test
    void hiddenInvalidStalePeopleAreStrippedBeforeDirectoryChecks() {
        var hidden = field("reviewer", "USER");
        hidden.put("visibleWhen", Map.of("op", "EQ", "field", "mode", "value", "REVIEW"));
        var schema = compiler.compile(schema(hidden, field("mode", "TEXT")));
        var binding = binding(schema.sha256());
        var evidence = evidence(binding, ApprovalFormUserDirectory.SOURCE_POLICY, "revision-1", UNTIL);
        var normalized = validator(ignored -> evidence).validate(binding, schema,
                Map.of("mode", "OTHER", "reviewer", "stale text"), true);
        assertThat(normalized.payload()).containsExactly(Map.entry("mode", "OTHER"));
        verify(directory, never()).resolve(any(), anyList());
    }

    @Test
    void deduplicatesAcrossRowsAndResolvesOnlyBoundedThirtyPersonBatches() {
        var schema = manyPeopleSchema();
        var binding = binding(schema.sha256());
        var evidence = evidence(binding, ApprovalFormUserDirectory.SOURCE_POLICY, "revision-1", UNTIL);
        List<UUID> ids = IntStream.range(1, 32).mapToObj(index -> new UUID(0L, index)).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        ids.forEach(id -> rows.add(Map.of("owner", id.toString())));
        rows.add(Map.of("owner", ids.getFirst().toString()));
        List<List<UUID>> batches = new ArrayList<>();
        when(directory.resolve(any(), anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(1);
            batches.add(batch);
            return new Result(evidence, batch.stream()
                    .map(id -> person(id, 10L, "TENANT", "ACTIVE", id.getLeastSignificantBits())).toList());
        });
        var result = validator(ignored -> evidence).validate(binding, schema, Map.of("lines", rows), false);
        assertThat(batches).hasSize(2);
        assertThat(batches.getFirst()).hasSize(30);
        assertThat(batches.getLast()).containsExactly(ids.getLast());
        assertThat((List<?>) result.payload().get("lines")).hasSize(32);
    }

    @Test
    void sourceMayNotMapTwoDifferentCanonicalPeopleToTheSameSubjectAcrossBatches() {
        var schema = manyPeopleSchema();
        var binding = binding(schema.sha256());
        var evidence = evidence(binding, ApprovalFormUserDirectory.SOURCE_POLICY, "revision-1", UNTIL);
        List<Map<String, Object>> rows = IntStream.range(1, 32)
                .mapToObj(index -> Map.<String, Object>of("owner", new UUID(0L, index).toString())).toList();
        when(directory.resolve(any(), anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(1);
            return new Result(evidence, batch.stream().map(id -> person(id, 10L, "TENANT", "ACTIVE",
                    id.getLeastSignificantBits() == 31 ? 1L : id.getLeastSignificantBits())).toList());
        });
        assertCode(ErrorCode.FORBIDDEN,
                () -> validator(ignored -> evidence).validate(binding, schema, Map.of("lines", rows), false));
    }

    @Test
    void authorityIsRecheckedBeforeTheNextBatchNotOnlyAtTheEnd() {
        var schema = manyPeopleSchema();
        var binding = binding(schema.sha256());
        var evidence = evidence(binding, ApprovalFormUserDirectory.SOURCE_POLICY, "revision-1", UNTIL);
        var calls = new AtomicInteger();
        var batches = new AtomicInteger();
        when(directory.resolve(any(), anyList())).thenAnswer(invocation -> {
            batches.incrementAndGet();
            List<UUID> ids = invocation.getArgument(1);
            return new Result(evidence, ids.stream()
                    .map(id -> person(id, 10L, "TENANT", "ACTIVE", id.getLeastSignificantBits())).toList());
        });
        List<Map<String, Object>> rows = IntStream.range(1, 32)
                .mapToObj(index -> Map.<String, Object>of("owner", new UUID(0L, index).toString())).toList();
        assertCode(ErrorCode.FORBIDDEN, () -> validator(ignored -> {
            if (calls.incrementAndGet() >= 3) throw new BaseException(ErrorCode.FORBIDDEN);
            return evidence;
        }).validate(binding, schema, Map.of("lines", rows), false));
        assertThat(batches.get()).isEqualTo(1);
    }

    private ApprovalFormUserValidator validator(ApprovalFormUserDirectory.AuthorityProvider provider) {
        return new ApprovalFormUserValidator(directory, provider, clock);
    }

    private ApprovalFormSchemaV2 manyPeopleSchema() {
        var lines = group("lines", List.of(field("owner", "USER")));
        lines.put("maxRows", 50);
        return compiler.compile(schema(lines));
    }

    private FormBinding binding(String hash) {
        return new FormBinding(10L, 20L, UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), hash);
    }

    private Authority evidence(FormBinding form, String policy, String revision, OffsetDateTime until) {
        return new Authority(form, policy, "approval-context", "opaque-scope", revision, "lookup-route", until);
    }

    private Person person(UUID id, Long tenant, String plane, String status, Long subject) {
        return new Person(tenant, subject, id, "Display only", plane, status);
    }

    private void assertCode(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BaseException.class)
                .satisfies(exception -> assertThat(((BaseException) exception).getErrorCode()).isEqualTo(code));
    }
}
