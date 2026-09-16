package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeRuntimeContextTest {

    @Test
    void canonicalizesAuthorityAndBindsFingerprintToTrustedDecision() {
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
        HomeRuntimeContext first = HomeRuntimeContext.create(
                11L, 22L, UUID.randomUUID(), "app.work:view, APP.CALENDAR:VIEW",
                "member", "team-b,team-a", "decision-7", future.toString(),
                "ko-KR", "Asia/Seoul");
        HomeRuntimeContext second = HomeRuntimeContext.create(
                11L, 22L, first.personPublicId(), "APP.CALENDAR:VIEW,APP.WORK:VIEW",
                "MEMBER", "team-a,team-b", "decision-7", future.toString(),
                "ko-KR", "Asia/Seoul");

        assertThat(first.permissionsHeader()).isEqualTo("APP.CALENDAR:VIEW,APP.WORK:VIEW");
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.has("app.work:view")).isTrue();
    }

    @Test
    void failsClosedWhenTrustedAuthorityEvidenceExpired() {
        assertThatThrownBy(() -> HomeRuntimeContext.create(
                1L, 2L, null, "APP.WORK:VIEW", "MEMBER", null,
                "decision-1", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                "ko-KR", "Asia/Seoul"))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void authorityExpiryBoundsCacheLifetimeWithoutChurningAuthorityFingerprint() {
        UUID personId = UUID.randomUUID();
        OffsetDateTime firstExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2);
        OffsetDateTime secondExpiry = firstExpiry.plusMinutes(1);
        HomeRuntimeContext first = HomeRuntimeContext.create(
                11L, 22L, personId, "APP.WORK:VIEW", "MEMBER", "team-a",
                "decision-7", firstExpiry.toString(), "ko-KR", "Asia/Seoul");
        HomeRuntimeContext second = HomeRuntimeContext.create(
                11L, 22L, personId, "APP.WORK:VIEW", "MEMBER", "team-a",
                "decision-7", secondExpiry.toString(), "ko-KR", "Asia/Seoul");

        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.authorityRevalidateAt()).isNotEqualTo(second.authorityRevalidateAt());
    }
}
