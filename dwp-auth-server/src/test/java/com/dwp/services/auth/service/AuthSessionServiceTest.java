package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.security.AuthSessionJwtTokenEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {

    private static final String SECRET =
            "test_secret_key_that_is_at_least_256_bits_long_for_hs256";

    private final AuthSessionRepository repository = mock(AuthSessionRepository.class);
    private final AuthSessionService service = new AuthSessionService(
            repository,
            new AuthSessionJwtTokenEncoder(
                    SECRET, new ObjectMapper().findAndRegisterModules()),
            28_800,
            1_800,
            600,
            30,
            60);

    @Test
    void createsARegistryRowWithoutStoringTheRawToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "DWP Test Browser");

        AuthSessionService.IssuedSession issued = service.create(
                7L,
                3L,
                List.of("EMPLOYEE"),
                request);

        ArgumentCaptor<AuthSession> captor = ArgumentCaptor.forClass(AuthSession.class);
        verify(repository).save(captor.capture());
        AuthSession saved = captor.getValue();
        assertThat(issued.accessToken()).isNotBlank();
        assertThat(saved.getTokenId()).isNotEqualTo(issued.accessToken());
        assertThat(saved.getSessionFamilyId()).isNotNull();
        assertThat(saved.getIdleExpiresAt()).isBefore(saved.getExpiresAt());
        assertThat(saved.getUserAgent()).isEqualTo("DWP Test Browser");
    }

    @Test
    void rejectsMixedProviderAndTenantRolesBeforeCreatingASession() {
        assertThatThrownBy(() -> service.create(
                        7L,
                        3L,
                        List.of("PROVIDER_ADMIN", "WORKSPACE_MEMBER"),
                        new MockHttpServletRequest()))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rotatesTokenIdWithoutExtendingIdleOrAbsoluteExpiry() {
        AuthSession previous = activeSession(Instant.now().minusSeconds(601));
        when(repository.findByTokenIdForUpdate(previous.getTokenId()))
                .thenReturn(Optional.of(previous));

        AuthSessionService.RotationResult result = service.rotate(
                jwt(previous),
                previous.getUserId(),
                previous.getTenantId(),
                List.of("EMPLOYEE"),
                new MockHttpServletRequest());

        ArgumentCaptor<AuthSession> captor = ArgumentCaptor.forClass(AuthSession.class);
        verify(repository).save(captor.capture());
        AuthSession current = captor.getValue();
        assertThat(result.response().rotated()).isTrue();
        assertThat(result.accessToken()).isNotBlank();
        assertThat(current.getTokenId()).isNotEqualTo(previous.getTokenId());
        assertThat(current.getSessionFamilyId()).isEqualTo(previous.getSessionFamilyId());
        assertThat(current.getIdleExpiresAt()).isEqualTo(previous.getIdleExpiresAt());
        assertThat(current.getExpiresAt()).isEqualTo(previous.getExpiresAt());
        assertThat(previous.getSupersededExpiresAt())
                .isAfter(previous.getSupersededAt())
                .isBeforeOrEqualTo(previous.getIdleExpiresAt());
    }

    @Test
    void doesNotRotateBeforeTheMinimumAge() {
        AuthSession current = activeSession(Instant.now().minusSeconds(60));
        when(repository.findByTokenIdForUpdate(current.getTokenId()))
                .thenReturn(Optional.of(current));

        AuthSessionService.RotationResult result = service.rotate(
                jwt(current),
                current.getUserId(),
                current.getTenantId(),
                List.of("EMPLOYEE"),
                new MockHttpServletRequest());

        assertThat(result.response().rotated()).isFalse();
        assertThat(result.accessToken()).isNull();
    }

    @Test
    void touchesTheCurrentFamilyAtomicallyAfterTheThrottleInterval() {
        AuthSession current = activeSession(Instant.now().minusSeconds(900));

        service.touch(current.getTokenId());

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> touchBefore = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> idleExpiresAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).touchCurrentByPresentedToken(
                org.mockito.ArgumentMatchers.eq(current.getTokenId()),
                now.capture(),
                touchBefore.capture(),
                idleExpiresAt.capture());
        assertThat(touchBefore.getValue()).isEqualTo(now.getValue().minusSeconds(60));
        assertThat(idleExpiresAt.getValue()).isEqualTo(now.getValue().plusSeconds(1_800));
    }

    @Test
    void revokesEveryTokenInTheSelectedSessionFamily() {
        AuthSession current = activeSession(Instant.now().minusSeconds(900));
        AuthSession previous = activeSession(Instant.now().minusSeconds(1_200));
        previous.setSessionFamilyId(current.getSessionFamilyId());
        when(repository.findByTokenId(current.getTokenId())).thenReturn(Optional.of(current));
        when(repository.findBySessionFamilyIdAndTenantIdAndUserIdAndRevokedAtIsNull(
                        current.getSessionFamilyId(), current.getTenantId(), current.getUserId()))
                .thenReturn(List.of(current, previous));

        boolean revokedCurrent = service.revokeFamily(
                current.getSessionFamilyId(),
                current.getTokenId(),
                current.getUserId(),
                current.getTenantId());

        assertThat(revokedCurrent).isTrue();
        assertThat(current.getRevokedAt()).isNotNull();
        assertThat(previous.getRevokedAt()).isNotNull();
    }

    @Test
    void rejectsASelectedSessionFamilyThatTheUserDoesNotOwn() {
        AuthSession current = activeSession(Instant.now().minusSeconds(900));
        UUID foreignFamilyId = UUID.randomUUID();
        when(repository.findByTokenId(current.getTokenId())).thenReturn(Optional.of(current));
        when(repository.findBySessionFamilyIdAndTenantIdAndUserIdAndRevokedAtIsNull(
                        foreignFamilyId, current.getTenantId(), current.getUserId()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.revokeFamily(
                        foreignFamilyId,
                        current.getTokenId(),
                        current.getUserId(),
                        current.getTenantId()))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void logoutOthersKeepsOnlyTheCurrentSessionFamily() {
        AuthSession current = activeSession(Instant.now().minusSeconds(900));
        AuthSession other = activeSession(Instant.now().minusSeconds(900));
        when(repository.findByTokenId(current.getTokenId())).thenReturn(Optional.of(current));
        when(repository.findByTenantIdAndUserIdAndRevokedAtIsNull(
                        current.getTenantId(), current.getUserId()))
                .thenReturn(List.of(current, other));

        service.revokeOthers(
                current.getTokenId(), current.getUserId(), current.getTenantId());

        assertThat(current.getRevokedAt()).isNull();
        assertThat(other.getRevokedAt()).isNotNull();
    }

    @Test
    void elevationRejectsUncanonicalizedAssuranceBeforeMutatingTheSessionFamily() {
        AuthSession current = activeSession(Instant.now().minusSeconds(60));
        when(repository.findByTokenIdForUpdate(current.getTokenId()))
                .thenReturn(Optional.of(current));
        AuthSessionService.AssuranceEvidence raw = new AuthSessionService.AssuranceEvidence(
                "OIDC_STEP_UP", Instant.now().minusSeconds(10), "urn:dwp:acr:mfa",
                List.of("otp", "pwd"));

        assertThatThrownBy(() -> service.elevate(
                jwt(current), current.getUserId(), current.getTenantId(), List.of("EMPLOYEE"),
                current.getSessionFamilyId(), raw, new MockHttpServletRequest()))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(
                        error.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));

        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(current.getSupersededAt()).isNull();
    }

    @Test
    void elevationRejectsNormalLoginEvenWhenTheProviderReturnedLiteralMfa() {
        AuthSession current = activeSession(Instant.now().minusSeconds(60));
        when(repository.findByTokenIdForUpdate(current.getTokenId()))
                .thenReturn(Optional.of(current));
        AuthSessionService.AssuranceEvidence login = new AuthSessionService.AssuranceEvidence(
                "OIDC", Instant.now().minusSeconds(10), "urn:dwp:acr:mfa", List.of("mfa"));

        assertThatThrownBy(() -> service.elevate(
                jwt(current), current.getUserId(), current.getTenantId(), List.of("EMPLOYEE"),
                current.getSessionFamilyId(), login, new MockHttpServletRequest()))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(
                        error.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));

        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(current.getSupersededAt()).isNull();
    }

    @Test
    void elevationPersistsCanonicalMfaAndOriginalProviderEvidence() {
        AuthSession current = activeSession(Instant.now().minusSeconds(60));
        when(repository.findByTokenIdForUpdate(current.getTokenId()))
                .thenReturn(Optional.of(current));
        AuthSessionService.AssuranceEvidence canonical =
                new AuthSessionService.AssuranceEvidence(
                        "OIDC_STEP_UP", Instant.now().minusSeconds(10), "urn:dwp:acr:mfa",
                        List.of("mfa", "otp", "pwd"));

        AuthSessionService.IssuedSession issued = service.elevate(
                jwt(current), current.getUserId(), current.getTenantId(), List.of("EMPLOYEE"),
                current.getSessionFamilyId(), canonical, new MockHttpServletRequest());

        ArgumentCaptor<AuthSession> saved = ArgumentCaptor.forClass(AuthSession.class);
        verify(repository).save(saved.capture());
        assertThat(issued.accessToken()).isNotBlank();
        assertThat(saved.getValue().getAssuranceAmr())
                .containsExactly("mfa", "otp", "pwd");
    }

    @Test
    void resolvesElevatedOnlyFromTheExactActiveDatabaseSessionAssurance() {
        AuthSession current = activeSession(Instant.now().minusSeconds(60));
        current.setAuthenticationMethod("OIDC_STEP_UP");
        current.setAuthenticatedAt(Instant.now().minusSeconds(30));
        current.setAssuranceAcr("urn:dwp:acr:mfa");
        current.setAssuranceAmr(List.of("mfa", "otp", "pwd"));
        when(repository.findByTokenId(current.getTokenId()))
                .thenReturn(Optional.of(current));

        assertThat(service.resolveActiveAccessMode(
                jwt(current), current.getUserId(), current.getTenantId(),
                "urn:dwp:acr:mfa", 600))
                .isEqualTo(AuthSessionService.ActiveAccessMode.ELEVATED);
    }

    @Test
    void deterministicallyDowngradesNonCanonicalOrStaleAssuranceToNormal() {
        AuthSession current = activeSession(Instant.now().minusSeconds(60));
        current.setAuthenticationMethod("OIDC_STEP_UP");
        current.setAuthenticatedAt(Instant.now().minusSeconds(30));
        current.setAssuranceAcr("urn:dwp:acr:mfa");
        current.setAssuranceAmr(List.of("mfa", "otp"));
        when(repository.findByTokenId(current.getTokenId()))
                .thenReturn(Optional.of(current));

        current.setAuthenticationMethod("OIDC");
        assertThat(resolveMode(current)).isEqualTo(AuthSessionService.ActiveAccessMode.NORMAL);
        current.setAuthenticationMethod("OIDC_STEP_UP");
        current.setAssuranceAcr("urn:dwp:acr:other");
        assertThat(resolveMode(current)).isEqualTo(AuthSessionService.ActiveAccessMode.NORMAL);
        current.setAssuranceAcr("urn:dwp:acr:mfa");
        current.setAssuranceAmr(List.of("otp", "pwd"));
        assertThat(resolveMode(current)).isEqualTo(AuthSessionService.ActiveAccessMode.NORMAL);
        current.setAssuranceAmr(List.of("mfa", "otp"));
        current.setAuthenticatedAt(Instant.now().minusSeconds(601));
        assertThat(resolveMode(current)).isEqualTo(AuthSessionService.ActiveAccessMode.NORMAL);
    }

    @Test
    void rejectsMissingInactiveOrIdentityMismatchedSessionsInsteadOfDowngrading() {
        AuthSession current = activeSession(Instant.now().minusSeconds(60));
        when(repository.findByTokenId(current.getTokenId()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolveMode(current))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(
                        error.getErrorCode()).isEqualTo(ErrorCode.TOKEN_INVALID));

        when(repository.findByTokenId(current.getTokenId()))
                .thenReturn(Optional.of(current));
        current.setRevokedAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> resolveMode(current))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(
                        error.getErrorCode()).isEqualTo(ErrorCode.TOKEN_INVALID));

        current.setRevokedAt(null);
        current.setSessionFamilyId(UUID.randomUUID());
        Jwt foreignFamily = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(String.valueOf(current.getUserId()))
                .claim("jti", current.getTokenId())
                .claim("tenant_id", String.valueOf(current.getTenantId()))
                .claim("sid", UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(current.getExpiresAt())
                .build();
        assertThatThrownBy(() -> service.resolveActiveAccessMode(
                foreignFamily, current.getUserId(), current.getTenantId(),
                "urn:dwp:acr:mfa", 600))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(
                        error.getErrorCode()).isEqualTo(ErrorCode.TOKEN_INVALID));
    }

    private AuthSessionService.ActiveAccessMode resolveMode(AuthSession session) {
        return service.resolveActiveAccessMode(
                jwt(session), session.getUserId(), session.getTenantId(),
                "urn:dwp:acr:mfa", 600);
    }

    private AuthSession activeSession(Instant issuedAt) {
        Instant now = Instant.now();
        return AuthSession.builder()
                .sessionId(UUID.randomUUID())
                .sessionFamilyId(UUID.randomUUID())
                .tokenId(UUID.randomUUID().toString())
                .tenantId(3L)
                .userId(7L)
                .sessionStartedAt(now.minusSeconds(900))
                .issuedAt(issuedAt)
                .lastSeenAt(now.minusSeconds(30))
                .idleExpiresAt(now.plusSeconds(1_200))
                .expiresAt(now.plusSeconds(7_200))
                .build();
    }

    private Jwt jwt(AuthSession session) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(String.valueOf(session.getUserId()))
                .claim("jti", session.getTokenId())
                .claim("tenant_id", String.valueOf(session.getTenantId()))
                .claim("sid", session.getSessionFamilyId().toString())
                .issuedAt(now)
                .expiresAt(session.getExpiresAt())
                .build();
    }
}
