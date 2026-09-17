package com.dwp.services.auth.scim;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.ScimConnector;
import com.dwp.services.auth.repository.ScimConnectorRepository;
import com.dwp.services.auth.service.IdentityAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScimCredentialGovernanceTest {
    private static final Instant NOW = Instant.parse("2026-09-17T02:00:00Z");
    private static final String PREFIX = "0123456789ab";
    private static final String TOKEN =
            "dwp_scim_" + PREFIX + "_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";

    private final ScimConnectorRepository repository = mock(ScimConnectorRepository.class);
    private final IdentityAuditService auditService = mock(IdentityAuditService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ScimCredentialService service = new ScimCredentialService(
            repository,
            jdbc,
            auditService,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearContext() {
        ScimConnectorContext.clear();
    }

    @Test
    void authenticatesOnlyBeforeHardExpiryAndCarriesRuntimeScopes() throws Exception {
        ScimConnector connector = connector(NOW.plusSeconds(3600), "[\"USERS\"]");
        when(repository.findByTokenPrefixAndLifecycleState(PREFIX, "ACTIVE"))
                .thenReturn(Optional.of(connector));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ScimConnectorContext.ConnectorIdentity identity = service.authenticate(TOKEN);

        assertThat(identity.allowedOperations()).containsExactly("USERS");
        assertThat(identity.tenantId()).isEqualTo(42L);
        ScimConnectorContext.set(identity);
        ScimConnectorContext.requireOperation("USERS");
        assertThatThrownBy(() -> ScimConnectorContext.requireOperation("GROUPS"))
                .isInstanceOfSatisfying(
                        ScimException.class,
                        error -> assertThat(error.status()).isEqualTo(403));
    }

    @Test
    void rejectsAtTheExactHardExpiryBoundaryAfterHashVerification() throws Exception {
        ScimConnector connector = connector(NOW, "[\"USERS\"]");
        when(repository.findByTokenPrefixAndLifecycleState(PREFIX, "ACTIVE"))
                .thenReturn(Optional.of(connector));

        assertThatThrownBy(() -> service.authenticate(TOKEN))
                .isInstanceOf(ScimCredentialService.ScimAuthenticationException.class);
        verify(repository, never()).save(connector);
    }

    @Test
    void acceptsCredentialUntilTheLastInstantBeforeExpiry() throws Exception {
        ScimConnector connector = connector(NOW.plusNanos(1), "[\"USERS\"]");
        when(repository.findByTokenPrefixAndLifecycleState(PREFIX, "ACTIVE"))
                .thenReturn(Optional.of(connector));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ScimConnectorContext.ConnectorIdentity identity = service.authenticate(TOKEN);

        assertThat(identity.connectorId()).isEqualTo(connector.getScimConnectorId());
        verify(jdbc).update(anyString(), any(Object[].class));
        verify(repository, never()).save(connector);
    }

    @Test
    void rejectsAuthenticationIfTheCredentialChangesBeforeTheUnversionedUsageTouch() throws Exception {
        ScimConnector connector = connector(NOW.plusSeconds(3600), "[\"USERS\"]");
        when(repository.findByTokenPrefixAndLifecycleState(PREFIX, "ACTIVE"))
                .thenReturn(Optional.of(connector));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> service.authenticate(TOKEN))
                .isInstanceOf(ScimCredentialService.ScimAuthenticationException.class);

        verify(repository, never()).save(connector);
    }

    @Test
    void createReturnsRawSecretOnceAndPersistsOnlyItsSha256Digest() throws Exception {
        when(repository.saveAndFlush(any(ScimConnector.class))).thenAnswer(invocation -> {
            ScimConnector connector = invocation.getArgument(0);
            connector.setScimConnectorId(UUID.fromString("47cf180a-e073-4fbf-804b-9e028be25a76"));
            connector.setVersion(0L);
            return connector;
        });

        ScimConnectorDtos.CredentialIssued issued = service.create(
                42L,
                7L,
                "correlation-create",
                new ScimConnectorDtos.CreateRequest(
                        "Entra-Production",
                        "Entra production",
                        "Provision workforce identities",
                        java.util.List.of("USERS", "GROUPS"),
                        180));

        ArgumentCaptor<ScimConnector> persisted = ArgumentCaptor.forClass(ScimConnector.class);
        verify(repository).saveAndFlush(persisted.capture());
        assertThat(issued.bearerToken()).startsWith("dwp_scim_");
        assertThat(persisted.getValue().getTokenHash()).isEqualTo(hash(issued.bearerToken()));
        assertThat(persisted.getValue().getTokenHash()).doesNotContain(issued.bearerToken());
        assertThat(persisted.getValue().getCredentialIssuedAt()).isEqualTo(NOW);
        assertThat(persisted.getValue().getCredentialExpiresAt())
                .isEqualTo(NOW.plus(Duration.ofDays(180)));
        assertThat(issued.connector().allowedOperations()).containsExactly("GROUPS", "USERS");
    }

    @Test
    void rejectsRotationWhenExpectedVersionIsStale() throws Exception {
        ScimConnector connector = connector(NOW.plus(Duration.ofDays(30)), "[\"USERS\"]");
        connector.setVersion(4L);
        when(repository.findByScimConnectorIdAndTenantId(connector.getScimConnectorId(), 42L))
                .thenReturn(Optional.of(connector));

        assertThatThrownBy(() -> service.rotate(
                42L,
                7L,
                "correlation-stale",
                connector.getScimConnectorId(),
                new ScimConnectorDtos.RotateRequest(3L, 90, true, "Scheduled rotation")))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(repository, never()).saveAndFlush(any(ScimConnector.class));
    }

    @Test
    void mapsDatabaseOptimisticLockRaceToAConflict() throws Exception {
        ScimConnector connector = connector(NOW.plus(Duration.ofDays(30)), "[\"USERS\"]");
        connector.setVersion(4L);
        when(repository.findByScimConnectorIdAndTenantId(connector.getScimConnectorId(), 42L))
                .thenReturn(Optional.of(connector));
        when(repository.saveAndFlush(connector))
                .thenThrow(new OptimisticLockingFailureException("concurrent rotation"));

        assertThatThrownBy(() -> service.rotate(
                42L,
                7L,
                "correlation-race",
                connector.getScimConnectorId(),
                new ScimConnectorDtos.RotateRequest(4L, 90, true, "Scheduled rotation")))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void rotationImmediatelyReplacesStoredDigestAndSetsANewHardExpiry() throws Exception {
        ScimConnector connector = connector(NOW.plus(Duration.ofDays(30)), "[\"USERS\"]");
        connector.setVersion(4L);
        String previousHash = connector.getTokenHash();
        when(repository.findByScimConnectorIdAndTenantId(connector.getScimConnectorId(), 42L))
                .thenReturn(Optional.of(connector));
        when(repository.saveAndFlush(connector)).thenReturn(connector);

        ScimConnectorDtos.CredentialIssued issued = service.rotate(
                42L,
                7L,
                "correlation-rotate",
                connector.getScimConnectorId(),
                new ScimConnectorDtos.RotateRequest(4L, 90, true, "Scheduled rotation"));

        assertThat(connector.getTokenHash()).isNotEqualTo(previousHash);
        assertThat(connector.getTokenHash()).isEqualTo(hash(issued.bearerToken()));
        assertThat(connector.getCredentialIssuedAt()).isEqualTo(NOW);
        assertThat(connector.getCredentialRotatedAt()).isEqualTo(NOW);
        assertThat(connector.getCredentialExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
        assertThat(issued.bearerToken()).doesNotContain(connector.getTokenHash());
        verify(auditService).success(
                eq(42L),
                eq(7L),
                eq("provisioning.scim-connector.rotated"),
                eq("SCIM_CONNECTOR"),
                eq(connector.getScimConnectorId().toString()),
                eq("correlation-rotate"),
                anyMap(),
                argThat(snapshot ->
                        "Scheduled rotation".equals(snapshot.get("reason"))
                                && !snapshot.toString().contains(issued.bearerToken())));
    }

    @Test
    void connectorIdentityDefensivelyCopiesItsScopes() {
        java.util.HashSet<String> scopes = new java.util.HashSet<>(Set.of("USERS"));
        ScimConnectorContext.ConnectorIdentity identity = new ScimConnectorContext.ConnectorIdentity(
                UUID.randomUUID(), 42L, "entra", scopes);

        scopes.add("GROUPS");

        assertThat(identity.allowedOperations()).containsExactly("USERS");
        assertThatThrownBy(() -> identity.allowedOperations().add("GROUPS"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ScimConnector connector(Instant expiresAt, String operations) throws Exception {
        return ScimConnector.builder()
                .scimConnectorId(UUID.randomUUID())
                .tenantId(42L)
                .connectorKey("entra")
                .displayName("Entra")
                .tokenPrefix(PREFIX)
                .tokenHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(TOKEN.getBytes(StandardCharsets.UTF_8))))
                .allowedOperations(operations)
                .purpose("Provision workforce identities")
                .ownerUserId(7L)
                .credentialIssuedAt(NOW.minusSeconds(60))
                .credentialExpiresAt(expiresAt)
                .lifecycleState("ACTIVE")
                .version(1L)
                .build();
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
