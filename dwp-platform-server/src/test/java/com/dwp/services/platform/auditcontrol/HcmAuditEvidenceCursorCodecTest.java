package com.dwp.services.platform.auditcontrol;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HcmAuditEvidenceCursorCodecTest {

    private static final Instant NOW = Instant.parse("2026-10-14T06:00:00Z");
    private static final String SECRET = "hcm-audit-cursor-secret-32-bytes-minimum";
    private static final UUID SUBJECT = UUID.fromString(
            "018f47a2-7b3c-7def-8abc-0123456789ab");
    private static final UUID EVENT = UUID.fromString(
            "018f47a2-7b3c-7def-8abc-0123456789ac");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HcmAuditEvidenceCursorCodec codec = new HcmAuditEvidenceCursorCodec(
            SECRET, Duration.ofMinutes(15), objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void roundTripsEveryTenantAuthorityPolicyAndSnapshotClaim() {
        HcmAuditEvidenceCriteria criteria = criteria(7L, NOW);
        HcmAuditEvidenceCursorCodec.AccessContext access = access(7L, 17, 29);
        Instant occurredAt = NOW.minusSeconds(30);

        String cursor = codec.encode(access, criteria, occurredAt, EVENT);
        HcmAuditEvidenceCursorCodec.CursorPosition result =
                codec.decode(cursor, access, criteria(7L, NOW.plusSeconds(10)));

        assertThat(result.snapshotAt()).isEqualTo(NOW);
        assertThat(result.occurredAt()).isEqualTo(occurredAt);
        assertThat(result.eventId()).isEqualTo(EVENT);
    }

    @Test
    void rejectsEveryTenantRouteContextScopeSubjectPolicyAndFilterMutation() {
        HcmAuditEvidenceCriteria criteria = criteria(7L, NOW);
        HcmAuditEvidenceCursorCodec.AccessContext access = access(7L, 17, 29);
        String cursor = codec.encode(access, criteria, NOW.minusSeconds(30), EVENT);

        assertInvalid(() -> codec.decode(cursor, access(8L, 17, 29), criteria(8L, NOW)));
        assertInvalid(() -> codec.decode(cursor, access(
                7L, "route.hcm.audit.other.page", "hcm.management",
                "hcm-scope-" + "b".repeat(40), SUBJECT, 17, 29), criteria));
        assertInvalid(() -> codec.decode(cursor, access(
                7L, "route.hcm.audit.evidence.page", "hcm.other",
                "hcm-scope-" + "b".repeat(40), SUBJECT, 17, 29), criteria));
        assertInvalid(() -> codec.decode(cursor, access(
                7L, "route.hcm.audit.evidence.page", "hcm.management",
                "hcm-scope-" + "c".repeat(40), SUBJECT, 17, 29), criteria));
        assertInvalid(() -> codec.decode(cursor, access(
                7L, "route.hcm.audit.evidence.page", "hcm.management",
                "hcm-scope-" + "b".repeat(40), UUID.fromString(
                        "018f47a2-7b3c-7def-8abc-0123456789ad"), 17, 29), criteria));
        assertInvalid(() -> codec.decode(cursor, access(7L, 18, 29), criteria));
        assertInvalid(() -> codec.decode(cursor, access(7L, 17, 30), criteria));
        assertInvalid(() -> codec.decode(cursor, access, HcmAuditEvidenceCriteria.of(
                7L, AuditWindow.D30, "ALL", "ALL", "ALL", null, null,
                50, NOW, Set.of("dwp-people-server"))));
        assertInvalid(() -> codec.decode(
                cursor.substring(0, cursor.length() - 1) + "A", access, criteria));
    }

    @Test
    void rejectsExpiredCursorAndMissingSigningConfiguration() {
        String cursor = codec.encode(
                access(7L, 17, 29), criteria(7L, NOW), NOW.minusSeconds(30), EVENT);
        HcmAuditEvidenceCursorCodec expired = new HcmAuditEvidenceCursorCodec(
                SECRET, Duration.ofMinutes(15), objectMapper,
                Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));
        assertInvalid(() -> expired.decode(cursor, access(7L, 17, 29), criteria(7L, NOW)));

        HcmAuditEvidenceCursorCodec unconfigured = new HcmAuditEvidenceCursorCodec(
                "short", Duration.ofMinutes(15), objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> unconfigured.encode(
                access(7L, 17, 29), criteria(7L, NOW), NOW, EVENT))
                .isInstanceOf(BaseException.class)
                .extracting(value -> ((BaseException) value).getErrorCode())
                .isEqualTo(com.dwp.core.common.ErrorCode.EXTERNAL_SERVICE_ERROR);
    }

    private HcmAuditEvidenceCriteria criteria(Long tenantId, Instant snapshot) {
        return HcmAuditEvidenceCriteria.of(
                tenantId, AuditWindow.D7, "ALL", "ALL", "ALL", null, null,
                50, snapshot, Set.of("dwp-people-server"));
    }

    private HcmAuditEvidenceCursorCodec.AccessContext access(
            Long tenantId, long fieldRevision, long authorizationRevision) {
        return access(
                tenantId,
                "route.hcm.audit.evidence.page",
                "hcm.management",
                "hcm-scope-" + "b".repeat(40),
                SUBJECT,
                fieldRevision,
                authorizationRevision);
    }

    private HcmAuditEvidenceCursorCodec.AccessContext access(
            Long tenantId,
            String route,
            String context,
            String scope,
            UUID subject,
            long fieldRevision,
            long authorizationRevision) {
        return new HcmAuditEvidenceCursorCodec.AccessContext(
                tenantId,
                route,
                context,
                scope,
                subject,
                fieldRevision,
                authorizationRevision);
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BaseException.class)
                .extracting(value -> ((BaseException) value).getErrorCode())
                .isEqualTo(com.dwp.core.common.ErrorCode.INVALID_INPUT_VALUE);
    }
}
