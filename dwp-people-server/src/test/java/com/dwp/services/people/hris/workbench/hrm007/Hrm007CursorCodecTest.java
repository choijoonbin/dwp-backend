package com.dwp.services.people.hris.workbench.hrm007;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm007CursorCodecTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void allSealedBindingsAndSignatureFailClosedWhenChanged() {
        Hrm007CursorCodec codec = codec(Clock.fixed(NOW, ZoneOffset.UTC));
        Hrm007CursorCodec.Binding issued = binding();
        String cursor = codec.issue(issued);

        assertThat(codec.verify(cursor, issued)).isEqualTo("change:100");
        List<Hrm007CursorCodec.Binding> mismatches = List.of(
                copy(issued, 42L, null, null, null, null, null, null, null, null),
                copy(issued, null, "IAQ-007", null, null, null, null, null, null, null),
                copy(issued, null, null, "APPROVED_DELEGATION:scope", null, null,
                        null, null, null, null),
                copy(issued, null, null, null, "scope:other", null,
                        null, null, null, null),
                copy(issued, null, null, null, null, "psr-" + "b".repeat(64),
                        null, null, null, null),
                copy(issued, null, null, null, null, null,
                        NOW.plusSeconds(1), null, null, null),
                copy(issued, null, null, null, null, null, null,
                        List.of("itemPublicId:DESC"), null, null),
                copy(issued, null, null, null, null, null, null,
                        null, 99, null),
                copy(issued, null, null, null, null, null, null,
                        null, null, "change:101"),
                new Hrm007CursorCodec.Binding(
                        issued.tenantId(), issued.queryId(), issued.targetPopulation(),
                        issued.scopeDecisionRevision(),
                        issued.authorizationDecisionRevision(), issued.asOf(),
                        issued.sort(), issued.pageLimit(), issued.lastItemPublicId(),
                        "another-filter", issued.locale(), issued.timeZone()),
                new Hrm007CursorCodec.Binding(
                        issued.tenantId(), issued.queryId(), issued.targetPopulation(),
                        issued.scopeDecisionRevision(),
                        issued.authorizationDecisionRevision(), issued.asOf(),
                        issued.sort(), issued.pageLimit(), issued.lastItemPublicId(),
                        issued.filterDigest(), "en-US", issued.timeZone()),
                new Hrm007CursorCodec.Binding(
                        issued.tenantId(), issued.queryId(), issued.targetPopulation(),
                        issued.scopeDecisionRevision(),
                        issued.authorizationDecisionRevision(), issued.asOf(),
                        issued.sort(), issued.pageLimit(), issued.lastItemPublicId(),
                        issued.filterDigest(), issued.locale(), "UTC"));
        mismatches.forEach(expected -> assertThatThrownBy(() -> codec.verify(cursor, expected))
                .isInstanceOf(Hrm007Exception.class));

        char replacement = cursor.charAt(cursor.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = cursor.substring(0, cursor.length() - 1) + replacement;
        assertThatThrownBy(() -> codec.verify(tampered, issued))
                .isInstanceOf(Hrm007Exception.class);
    }

    @Test
    void expiryAndMissingSecretFailClosed() {
        String cursor = codec(Clock.fixed(NOW, ZoneOffset.UTC)).issue(binding());
        Hrm007CursorCodec expired = codec(
                Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));
        Hrm007CursorCodec missing = new Hrm007CursorCodec(
                "short", Duration.ofMinutes(15), mapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> expired.verify(cursor, binding()))
                .isInstanceOf(Hrm007Exception.class);
        assertThatThrownBy(() -> missing.issue(binding()))
                .isInstanceOf(Hrm007Exception.class);
    }

    private Hrm007CursorCodec codec(Clock clock) {
        return new Hrm007CursorCodec(
                "0123456789abcdef0123456789abcdef", Duration.ofMinutes(15),
                mapper(), clock);
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private Hrm007CursorCodec.Binding binding() {
        return new Hrm007CursorCodec.Binding(
                41L, "IAQ-005", "DIRECT_REPORTS:scope", "scope:17",
                "psr-" + "a".repeat(64), NOW, List.of("itemPublicId:ASC"),
                100, "change:100", "filter", "ko-KR", "Asia/Seoul");
    }

    private Hrm007CursorCodec.Binding copy(
            Hrm007CursorCodec.Binding source,
            Long tenant,
            String query,
            String population,
            String scope,
            String authorization,
            Instant asOf,
            List<String> sort,
            Integer limit,
            String lastItem) {
        return new Hrm007CursorCodec.Binding(
                tenant == null ? source.tenantId() : tenant,
                query == null ? source.queryId() : query,
                population == null ? source.targetPopulation() : population,
                scope == null ? source.scopeDecisionRevision() : scope,
                authorization == null
                        ? source.authorizationDecisionRevision() : authorization,
                asOf == null ? source.asOf() : asOf,
                sort == null ? source.sort() : sort,
                limit == null ? source.pageLimit() : limit,
                lastItem == null ? source.lastItemPublicId() : lastItem,
                source.filterDigest(), source.locale(), source.timeZone());
    }
}
