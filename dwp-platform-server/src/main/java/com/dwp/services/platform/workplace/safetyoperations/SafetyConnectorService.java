package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyIncidentRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@Service
public class SafetyConnectorService {
    private final SafetyIncidentRepository repository;
    private final ObjectMapper mapper;
    private final Duration freshness;
    private final Clock clock;

    @Autowired
    public SafetyConnectorService(
            SafetyIncidentRepository repository,
            ObjectMapper mapper,
            @Value("${dwp.workplace.safety.connector-freshness:PT5M}") Duration freshness) {
        this(repository, mapper, freshness, Clock.systemUTC());
    }

    SafetyConnectorService(SafetyIncidentRepository repository, ObjectMapper mapper,
                           Duration freshness, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        if (freshness.isNegative() || freshness.isZero()) {
            throw new IllegalArgumentException("connector freshness must be positive");
        }
        this.freshness = freshness;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ConnectorTruth> truth(long tenantId) {
        if (tenantId <= 0) throw invalid("A positive tenant ID is required.");
        Map<ConnectorKind, ConnectorRow> rows = new EnumMap<>(ConnectorKind.class);
        repository.connectors(tenantId).forEach(row -> rows.put(row.kind(), row));
        OffsetDateTime now = now();
        List<ConnectorTruth> result = new ArrayList<>();
        for (ConnectorKind kind : ConnectorKind.values()) result.add(truth(kind, rows.get(kind), now));
        return List.copyOf(result);
    }

    @Transactional
    public ConnectorCommandResult configure(
            long tenantId, long actorId, String idempotencyKey,
            ConnectorConfigurationRequest request, String correlationId) {
        if (tenantId <= 0 || actorId <= 0) throw invalid("Positive tenant and actor IDs are required.");
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        String key = key(idempotencyKey);
        String fingerprint = fingerprint(request);
        repository.lockCommandKey(tenantId, actorId, "CONFIGURE_CONNECTOR", key);
        CommandRow duplicate = repository.command(
                tenantId, actorId, "CONFIGURE_CONNECTOR", key).orElse(null);
        if (duplicate != null) {
            if (!duplicate.fingerprint().equals(fingerprint)) {
                throw conflict("The idempotency key was used for another connector command.");
            }
            ConnectorRow replay = repository.connector(tenantId, request.kind())
                    .orElseThrow(() -> conflict("The replayed connector configuration is missing."));
            return new ConnectorCommandResult(truth(request.kind(), replay, now()),
                    receipt(duplicate, true));
        }
        ConnectorRow existing = repository.connector(tenantId, request.kind()).orElse(null);
        long current = existing == null ? 0 : existing.version();
        if (current != request.expectedVersion()) throw conflict("The connector version changed.");
        OffsetDateTime now = now();
        String correlation = SafetyRepositorySupport.correlation(correlationId);
        CommandRow command = repository.insertCommand(tenantId, actorId, null,
                "CONFIGURE_CONNECTOR", key, fingerprint, request.reason(), correlation,
                "/v1/admin/workplace/safety/connectors/" + request.kind(), now);
        ConnectorRow row = repository.configureConnector(tenantId, request, now);
        repository.completeLocalCommand(tenantId, command.id(), "CONNECTOR_CONFIGURED", now);
        repository.audit(tenantId, null, actorId, "safety.connector.configured",
                "CONNECTOR", row.id(), correlation,
                Map.of("kind", request.kind().name(), "configured", request.configured()), now);
        return new ConnectorCommandResult(truth(request.kind(), row, now),
                receipt(succeeded(command, now), false));
    }

    @Transactional(readOnly = true)
    public CommandReceipt command(long tenantId, java.util.UUID commandId) {
        CommandRow command = repository.command(tenantId, commandId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND,
                        "The safety connector command was not found."));
        return receipt(command, false);
    }

    @Transactional
    public boolean observe(ConnectorObservation observation) {
        if (observation.tenantId() <= 0 || observation.sourceAt().isAfter(observation.receivedAt())) {
            throw invalid("The connector observation has an invalid tenant or clock ordering.");
        }
        return repository.observeConnector(observation, now());
    }

    ConnectorKind requiredConnector(DeliveryChannel channel) {
        return switch (channel) {
            case EBS -> ConnectorKind.EBS;
            case BLE_MESH -> ConnectorKind.BLE_MESH;
            default -> null;
        };
    }

    SafetyIncidentRepository.ConnectorRow configuredConnector(
            long tenantId, DeliveryChannel channel) {
        ConnectorKind required = requiredConnector(channel);
        if (required == null) return null;
        SafetyIncidentRepository.ConnectorRow row = repository.connector(tenantId, required)
                .orElse(null);
        return row != null && row.configured() ? row : null;
    }

    private ConnectorTruth truth(ConnectorKind kind, ConnectorRow row, OffsetDateTime now) {
        if (row == null || !row.configured()) {
            return new ConnectorTruth(kind, row == null ? null : row.provider(),
                    ConnectorTruthState.NOT_CONFIGURED, row == null ? 0 : row.configurationVersion(),
                    null, null, null, null, null, null, row == null ? 0 : row.version(), now);
        }
        boolean verified = row.observedVersion() != null
                && row.observedVersion() == row.configurationVersion()
                && row.reportedState() != null && row.evidenceReference() != null
                && row.sourceAt() != null && row.receivedAt() != null;
        ConnectorTruthState state;
        if (!verified) state = ConnectorTruthState.CONFIGURED_UNVERIFIED;
        else if (row.sourceAt().isBefore(now.minus(freshness))) state = ConnectorTruthState.STALE;
        else if ("READY".equals(row.reportedState())) state = ConnectorTruthState.READY;
        else state = ConnectorTruthState.DEGRADED;
        return new ConnectorTruth(kind, row.provider(), state, row.configurationVersion(),
                row.observedVersion(), row.evidenceReference(), row.sourceAt(), row.receivedAt(),
                row.lastSuccessAt(), row.errorCode(), row.version(), now);
    }

    private String fingerprint(ConnectorConfigurationRequest request) {
        try {
            return SafetyRepositorySupport.sha256(
                    "CONFIGURE_CONNECTOR:" + mapper.writeValueAsString(request));
        } catch (JsonProcessingException exception) {
            throw invalid("The connector configuration command is invalid.");
        }
    }

    private static CommandReceipt receipt(CommandRow row, boolean replay) {
        return new CommandReceipt(row.id(), row.state(), row.statusHref(), replay,
                row.correlationId(), row.acceptedAt());
    }

    private static CommandRow succeeded(CommandRow row, OffsetDateTime now) {
        return new CommandRow(row.id(), null, row.type(), row.fingerprint(),
                CommandState.SUCCEEDED, row.reason(), row.correlationId(), row.statusHref(),
                "CONNECTOR_CONFIGURED", null, row.version() + 1,
                row.acceptedAt(), now, now);
    }

    private static String key(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 160) {
            throw invalid("A bounded Idempotency-Key is required.");
        }
        return value.trim();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }
}
