package com.dwp.services.auth.scim;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.ScimConnector;
import com.dwp.services.auth.repository.ScimConnectorRepository;
import com.dwp.services.auth.service.IdentityAuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ScimCredentialService {

    private static final String ACTIVE = "ACTIVE";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ScimConnectorRepository repository;
    private final IdentityAuditService auditService;

    public ScimCredentialService(
            ScimConnectorRepository repository,
            IdentityAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public ScimConnectorDtos.CredentialIssued create(
            Long tenantId,
            Long actorId,
            String correlationId,
            ScimConnectorDtos.CreateRequest request) {
        IssuedToken issued = issueToken();
        ScimConnector connector = ScimConnector.builder()
                .tenantId(tenantId)
                .connectorKey(request.connectorKey().trim().toLowerCase(java.util.Locale.ROOT))
                .displayName(request.displayName().trim())
                .tokenPrefix(issued.prefix())
                .tokenHash(hash(issued.token()))
                .allowedOperations("[\"USERS\",\"GROUPS\"]")
                .lifecycleState(ACTIVE)
                .build();
        connector.setCreatedBy(actorId);
        connector.setUpdatedBy(actorId);
        try {
            connector = repository.saveAndFlush(connector);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A SCIM connector with this key already exists.",
                    exception);
        }
        auditService.success(
                tenantId, actorId, "provisioning.scim-connector.created",
                "SCIM_CONNECTOR", connector.getScimConnectorId().toString(), correlationId,
                null, snapshot(connector));
        return new ScimConnectorDtos.CredentialIssued(summary(connector), issued.token());
    }

    @Transactional(readOnly = true)
    public List<ScimConnectorDtos.ConnectorSummary> list(Long tenantId) {
        return repository.findByTenantIdOrderByConnectorKeyAsc(tenantId)
                .stream().map(this::summary).toList();
    }

    @Transactional
    public ScimConnectorDtos.CredentialIssued rotate(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID connectorId) {
        ScimConnector connector = require(tenantId, connectorId);
        if ("RETIRED".equals(connector.getLifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "A retired SCIM connector cannot be rotated.");
        }
        Map<String, Object> before = snapshot(connector);
        IssuedToken issued = issueToken();
        connector.setTokenPrefix(issued.prefix());
        connector.setTokenHash(hash(issued.token()));
        connector.setLifecycleState(ACTIVE);
        connector.setUpdatedBy(actorId);
        connector = repository.saveAndFlush(connector);
        auditService.success(
                tenantId, actorId, "provisioning.scim-connector.rotated",
                "SCIM_CONNECTOR", connectorId.toString(), correlationId,
                before, snapshot(connector));
        return new ScimConnectorDtos.CredentialIssued(summary(connector), issued.token());
    }

    @Transactional
    public ScimConnectorDtos.ConnectorSummary lifecycle(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID connectorId,
            String state) {
        ScimConnector connector = require(tenantId, connectorId);
        String normalized = state.trim().toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> before = snapshot(connector);
        connector.setLifecycleState(normalized);
        connector.setUpdatedBy(actorId);
        connector = repository.saveAndFlush(connector);
        auditService.success(
                tenantId, actorId, "provisioning.scim-connector.lifecycle-changed",
                "SCIM_CONNECTOR", connectorId.toString(), correlationId,
                before, snapshot(connector));
        return summary(connector);
    }

    @Transactional
    public ScimConnectorContext.ConnectorIdentity authenticate(String bearerToken) {
        ParsedToken parsed = parse(bearerToken);
        ScimConnector connector = repository
                .findByTokenPrefixAndLifecycleState(parsed.prefix(), ACTIVE)
                .orElseThrow(ScimAuthenticationException::new);
        byte[] expected = connector.getTokenHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash(bearerToken).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) throw new ScimAuthenticationException();
        Instant now = Instant.now();
        if (connector.getLastUsedAt() == null
                || connector.getLastUsedAt().isBefore(now.minus(Duration.ofMinutes(5)))) {
            connector.setLastUsedAt(now);
            repository.save(connector);
        }
        return new ScimConnectorContext.ConnectorIdentity(
                connector.getScimConnectorId(), connector.getTenantId(), connector.getConnectorKey());
    }

    private ScimConnector require(Long tenantId, UUID connectorId) {
        return repository.findByScimConnectorIdAndTenantId(connectorId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private IssuedToken issueToken() {
        byte[] prefixBytes = new byte[6];
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(prefixBytes);
        RANDOM.nextBytes(secretBytes);
        String prefix = HexFormat.of().formatHex(prefixBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        return new IssuedToken(prefix, "dwp_scim_" + prefix + "_" + secret);
    }

    private ParsedToken parse(String token) {
        if (token == null || !token.startsWith("dwp_scim_")) throw new ScimAuthenticationException();
        String[] parts = token.split("_", 4);
        if (parts.length != 4 || parts[2].length() != 12 || parts[3].length() < 32) {
            throw new ScimAuthenticationException();
        }
        return new ParsedToken(parts[2]);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private ScimConnectorDtos.ConnectorSummary summary(ScimConnector connector) {
        return new ScimConnectorDtos.ConnectorSummary(
                connector.getScimConnectorId(), connector.getConnectorKey(),
                connector.getDisplayName(), connector.getTokenPrefix(),
                List.of("USERS", "GROUPS"), connector.getLifecycleState(),
                connector.getLastUsedAt(), valueOrZero(connector.getVersion()));
    }

    private Map<String, Object> snapshot(ScimConnector connector) {
        return Map.of(
                "connectorId", connector.getScimConnectorId().toString(),
                "connectorKey", connector.getConnectorKey(),
                "lifecycleState", connector.getLifecycleState(),
                "tokenPrefix", connector.getTokenPrefix());
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private record IssuedToken(String prefix, String token) {
    }

    private record ParsedToken(String prefix) {
    }

    public static final class ScimAuthenticationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
