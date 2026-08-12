package com.dwp.services.platform.productivity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.dwp.services.platform.productivity.ProductivityTypes.*;

@Service
public class ProductivityService {

    private static final Set<String> ALLOWED_SCOPES = Set.of(
            "openid", "profile", "offline_access", "User.Read",
            "Mail.ReadBasic", "Calendars.Read");
    private static final Set<String> REQUIRED_SCOPES = Set.of(
            "openid", "offline_access", "User.Read", "Mail.ReadBasic", "Calendars.Read");
    private static final List<String> CAPABILITIES = List.of(
            "MAIL_METADATA", "CALENDAR_EVENTS", "DELTA_SYNC", "DEEP_LINK");
    private static final Pattern PROVIDER_TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,159}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProductivityRepository repository;
    private final ProductivityCrypto crypto;
    private final ProductivityCredentialResolver credentialResolver;
    private final MicrosoftGraphClient graph;
    private final PlatformAuditService audit;
    private final int maxPagesPerRun;

    public ProductivityService(
            ProductivityRepository repository,
            ProductivityCrypto crypto,
            ProductivityCredentialResolver credentialResolver,
            MicrosoftGraphClient graph,
            PlatformAuditService audit,
            @Value("${dwp.platform.productivity.sync.max-pages-per-run:10}") int maxPagesPerRun) {
        this.repository = repository;
        this.crypto = crypto;
        this.credentialResolver = credentialResolver;
        this.graph = graph;
        this.audit = audit;
        this.maxPagesPerRun = Math.min(50, Math.max(1, maxPagesPerRun));
    }

    public ProductivityDtos.Overview overview(Long tenantId) {
        ProductivityRepository.Metrics metrics = repository.metrics(tenantId);
        return new ProductivityDtos.Overview(
                metrics.connectors(),
                metrics.activeConnectors(),
                metrics.connectedSubjects(),
                metrics.staleStreams(),
                metrics.failedRuns24h(),
                metrics.lastSuccessfulSyncAt(),
                connectors(tenantId),
                runs(tenantId, 12));
    }

    public List<ProductivityDtos.Connector> connectors(Long tenantId) {
        return repository.connectors(tenantId).stream().map(this::connector).toList();
    }

    public ProductivityDtos.Connector createConnector(
            Long tenantId,
            Long actorId,
            String correlationId,
            ProductivityDtos.SaveConnectorRequest request) {
        ProductivityRepository.ConnectorDraft draft = draft(request);
        try {
            ProductivityRepository.ConnectorRecord created = repository.createConnector(
                    tenantId, actorId, draft);
            audit.success(
                    tenantId, actorId, "productivity.connector.created", "PRODUCTIVITY_CONNECTOR",
                    created.connectorId().toString(), correlationId, null, snapshot(created));
            return connector(created);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "A productivity connector with this key already exists.");
        }
    }

    public ProductivityDtos.Connector updateConnector(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID connectorId,
            ProductivityDtos.SaveConnectorRequest request) {
        if (request.version() == null || request.version() < 0) {
            throw new BaseException(ErrorCode.VALIDATION_ERROR, "Connector version is required.");
        }
        ProductivityRepository.ConnectorRecord before = requireConnector(tenantId, connectorId);
        try {
            ProductivityRepository.ConnectorRecord updated = repository.updateConnector(
                    tenantId, actorId, connectorId, request.version(), draft(request))
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.RESOURCE_CONFLICT,
                            "The connector changed or must be suspended before editing."));
            audit.success(
                    tenantId, actorId, "productivity.connector.updated", "PRODUCTIVITY_CONNECTOR",
                    connectorId.toString(), correlationId, snapshot(before), snapshot(updated));
            return connector(updated);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "A productivity connector with this key already exists.");
        }
    }

    public ProductivityDtos.ConfigurationCheck checkConfiguration(Long tenantId, UUID connectorId) {
        ProductivityRepository.ConnectorRecord connector = requireConnector(tenantId, connectorId);
        List<String> checks = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        check(!isBlank(connector.clientId()) && validUuid(connector.clientId()),
                "CLIENT_ID_FORMAT", checks, blocking);
        check(!isBlank(connector.providerTenantId())
                        && PROVIDER_TENANT.matcher(connector.providerTenantId()).matches(),
                "PROVIDER_TENANT_FORMAT", checks, blocking);
        check(validRedirect(connector.redirectUri()), "REDIRECT_URI_POLICY", checks, blocking);
        check(credentialResolver.validReference(connector.credentialReference()),
                "CREDENTIAL_REFERENCE_POLICY", checks, blocking);
        check(credentialResolver.resolve(connector.credentialReference()).isPresent(),
                "CLIENT_SECRET_AVAILABLE", checks, blocking);
        check(crypto.available(), "DATA_ENCRYPTION_KEY_AVAILABLE", checks, blocking);
        check(new LinkedHashSet<>(connector.requestedScopes()).containsAll(REQUIRED_SCOPES),
                "REQUIRED_DELEGATED_SCOPES", checks, blocking);
        check(connector.requestedScopes().stream().allMatch(ALLOWED_SCOPES::contains),
                "LEAST_PRIVILEGE_SCOPE_POLICY", checks, blocking);
        check(connector.policyState() == PolicyState.APPROVED,
                "TENANT_POLICY_APPROVAL", checks, blocking);

        Instant checkedAt = Instant.now();
        boolean ready = blocking.isEmpty();
        ConnectorHealth health = ready
                ? ConnectorHealth.DEGRADED
                : ConnectorHealth.CONFIGURATION_REQUIRED;
        String errorCode = ready ? "AWAITING_FIRST_SUCCESSFUL_SYNC" : blocking.get(0);
        repository.configurationResult(tenantId, connectorId, health, errorCode, checkedAt);
        return new ProductivityDtos.ConfigurationCheck(
                connectorId, ready, health, List.copyOf(checks), List.copyOf(blocking), checkedAt);
    }

    public ProductivityDtos.Connector activate(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID connectorId,
            long version) {
        ProductivityRepository.ConnectorRecord before = requireConnector(tenantId, connectorId);
        if (before.version() != version) throw conflict();
        ProductivityDtos.ConfigurationCheck check = checkConfiguration(tenantId, connectorId);
        if (!check.ready()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Connector configuration has unresolved policy or credential checks.");
        }
        ProductivityRepository.ConnectorRecord checked = requireConnector(tenantId, connectorId);
        if (!repository.changeLifecycle(
                tenantId, actorId, connectorId, checked.version(), ConnectorLifecycle.ACTIVE)) {
            throw conflict();
        }
        ProductivityRepository.ConnectorRecord active = requireConnector(tenantId, connectorId);
        audit.success(
                tenantId, actorId, "productivity.connector.activated", "PRODUCTIVITY_CONNECTOR",
                connectorId.toString(), correlationId, snapshot(before), snapshot(active));
        return connector(active);
    }

    public ProductivityDtos.Connector suspend(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID connectorId,
            long version) {
        ProductivityRepository.ConnectorRecord before = requireConnector(tenantId, connectorId);
        if (!repository.changeLifecycle(
                tenantId, actorId, connectorId, version, ConnectorLifecycle.SUSPENDED)) {
            throw conflict();
        }
        ProductivityRepository.ConnectorRecord suspended = requireConnector(tenantId, connectorId);
        audit.success(
                tenantId, actorId, "productivity.connector.suspended", "PRODUCTIVITY_CONNECTOR",
                connectorId.toString(), correlationId, snapshot(before), snapshot(suspended));
        return connector(suspended);
    }

    public List<ProductivityDtos.Subject> subjects(Long tenantId, int limit) {
        return repository.subjects(tenantId, limit).stream().map(this::subject).toList();
    }

    public List<ProductivityDtos.SyncRun> runs(Long tenantId, int limit) {
        return repository.runs(tenantId, limit).stream().map(this::run).toList();
    }

    public List<ProductivityDtos.Connection> connections(Long tenantId, Long userId) {
        return repository.connectors(tenantId).stream()
                .filter(value -> value.lifecycleState() != ConnectorLifecycle.RETIRED)
                .map(value -> connection(value, repository.subject(
                        tenantId, value.connectorId(), userId).orElse(null)))
                .toList();
    }

    public ProductivityDtos.AuthorizationStart beginAuthorization(
            Long tenantId,
            Long userId,
            UUID connectorId) {
        ProductivityRepository.ConnectorRecord connector = requireUsableConnector(tenantId, connectorId);
        ConfigurationReadiness readiness = readiness(connector);
        if (!readiness.ready()) {
            throw new BaseException(ErrorCode.INVALID_STATE, readiness.errorCode());
        }
        String state = randomUrlValue(32);
        String verifier = randomUrlValue(48);
        String challenge = sha256Url(verifier);
        String stateHash = crypto.fingerprint(state);
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        String encryptedVerifier = crypto.encrypt(
                verifier, oauthAad(tenantId, userId, stateHash));
        ProductivityRepository.OAuthTransaction transaction = repository.createOAuthTransaction(
                tenantId, userId, connectorId, stateHash, encryptedVerifier, expiresAt);
        URI authorizationUri = graph.authorizationUri(connector, state, challenge);
        return new ProductivityDtos.AuthorizationStart(
                transaction.transactionId(), authorizationUri.toString(), expiresAt);
    }

    public ProductivityDtos.Connection completeAuthorization(
            Long tenantId,
            Long userId,
            String correlationId,
            ProductivityDtos.AuthorizationCallbackRequest request) {
        String stateHash = crypto.fingerprint(request.state());
        ProductivityRepository.OAuthTransaction transaction = repository.consumeOAuthTransaction(
                tenantId, userId, stateHash, Instant.now())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_STATE, "OAuth transaction is expired or already consumed."));
        ProductivityRepository.ConnectorRecord connector = requireUsableConnector(
                tenantId, transaction.connectorId());
        String secret = credentialResolver.resolve(connector.credentialReference())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_STATE, "Connector credential is not available."));
        String verifier = crypto.decrypt(
                transaction.encryptedPkceVerifier(), oauthAad(tenantId, userId, stateHash));
        MicrosoftGraphClient.TokenResponse token;
        String providerSubject;
        try {
            token = graph.exchangeCode(connector, secret, request.code(), verifier);
            if (isBlank(token.refreshToken())) {
                throw new MicrosoftGraphClient.GraphFailure(
                        "OAUTH_OFFLINE_CREDENTIAL_MISSING",
                        ConnectorHealth.AUTHENTICATION_REQUIRED,
                        null,
                        false,
                        false);
            }
            providerSubject = graph.subjectReference(token.accessToken());
        } catch (MicrosoftGraphClient.GraphFailure failure) {
            repository.recordConnectorFailure(
                    tenantId, connector.connectorId(), failure.health(), failure.code());
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR, safeMessage(failure.code()));
        }
        String encryptedRefreshToken = crypto.encrypt(
                token.refreshToken(), subjectTokenAad(tenantId, userId, connector.connectorId()));
        List<String> scopes = token.scopes().isEmpty()
                ? connector.requestedScopes()
                : token.scopes();
        ProductivityRepository.SubjectRecord subject = repository.connectSubject(
                tenantId,
                userId,
                connector.connectorId(),
                crypto.fingerprint(providerSubject),
                encryptedRefreshToken,
                scopes,
                token.expiresAt());
        Instant now = Instant.now();
        repository.ensureStream(
                tenantId, userId, subject.subjectId(), ResourceKind.MAIL, null, null);
        repository.ensureStream(
                tenantId, userId, subject.subjectId(), ResourceKind.CALENDAR,
                now.minus(7, ChronoUnit.DAYS), now.plus(90, ChronoUnit.DAYS));
        audit.success(
                tenantId, userId, "productivity.subject.connected", "PRODUCTIVITY_SUBJECT",
                subject.subjectId().toString(), correlationId, null,
                Map.of("connectorId", connector.connectorId(), "consentState", "CONNECTED",
                        "grantedScopes", scopes));
        return connection(connector, subject);
    }

    public ProductivityDtos.SyncRun sync(
            Long tenantId,
            Long userId,
            String correlationId,
            UUID connectorId,
            ProductivityDtos.SyncRequest request) {
        ProductivityRepository.ConnectorRecord connector = requireUsableConnector(tenantId, connectorId);
        ProductivityRepository.SubjectRecord subject = repository.subject(
                tenantId, connectorId, userId).orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_STATE, "Delegated user authorization is required."));
        if (subject.consentState() != ConsentState.CONNECTED) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Delegated authorization must be renewed.");
        }
        ProductivityRepository.StreamRecord stream = repository.ensureStream(
                tenantId,
                userId,
                subject.subjectId(),
                request.resourceKind(),
                request.resourceKind() == ResourceKind.CALENDAR
                        ? Instant.now().minus(7, ChronoUnit.DAYS) : null,
                request.resourceKind() == ResourceKind.CALENDAR
                        ? Instant.now().plus(90, ChronoUnit.DAYS) : null);
        if (!repository.startStream(stream.streamId(), request.reset())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "This sync stream is already running.");
        }

        SyncMode mode = request.reset()
                ? SyncMode.RESET
                : stream.encryptedCursor() == null ? SyncMode.INITIAL : SyncMode.DELTA;
        UUID runId = repository.startRun(
                tenantId, userId, connectorId, subject.subjectId(), request.resourceKind(),
                mode, correlationId, Instant.now());
        try {
            ConfigurationReadiness readiness = readiness(connector);
            if (!readiness.ready()) {
                throw new MicrosoftGraphClient.GraphFailure(
                        readiness.errorCode(), ConnectorHealth.CONFIGURATION_REQUIRED,
                        null, false, false);
            }
            String secret = credentialResolver.resolve(connector.credentialReference()).orElseThrow();
            String refreshToken = crypto.decrypt(
                    subject.encryptedRefreshToken(), subjectTokenAad(tenantId, userId, connectorId));
            MicrosoftGraphClient.TokenResponse token = graph.refresh(connector, secret, refreshToken);
            String updatedRefreshToken = isBlank(token.refreshToken()) ? null : crypto.encrypt(
                    token.refreshToken(), subjectTokenAad(tenantId, userId, connectorId));
            repository.updateSubjectToken(
                    subject.subjectId(), updatedRefreshToken, token.expiresAt(),
                    token.scopes().isEmpty() ? subject.grantedScopes() : token.scopes());

            String cursor = request.reset() || stream.encryptedCursor() == null
                    ? null
                    : crypto.decrypt(stream.encryptedCursor(), cursorAad(tenantId, subject, request.resourceKind()));
            SyncCounts counts = synchronizePages(
                    tenantId, userId, connector, subject, stream, request.resourceKind(),
                    token.accessToken(), cursor, runId);
            String encryptedCursor = counts.cursor() == null ? null : crypto.encrypt(
                    counts.cursor(), cursorAad(tenantId, subject, request.resourceKind()));
            String cursorFingerprint = counts.cursor() == null ? null : crypto.fingerprint(counts.cursor());
            repository.completeStream(
                    stream.streamId(), encryptedCursor, cursorFingerprint,
                    counts.partial() ? StreamState.STALE : StreamState.READY,
                    counts.partial() ? "PAGE_LIMIT_REACHED" : null, true);
            repository.completeRun(
                    runId,
                    counts.partial() ? SyncRunState.PARTIAL : SyncRunState.SUCCEEDED,
                    counts.upserts(), counts.deletes(), counts.skips(), counts.errors(),
                    counts.partial(), null, counts.partial() ? "PAGE_LIMIT_REACHED" : null);
            repository.recordSyncSuccess(tenantId, connectorId, subject.subjectId());
            audit.success(
                    tenantId, userId, "productivity.sync.completed", "PRODUCTIVITY_SYNC_RUN",
                    runId.toString(), correlationId, null,
                    Map.of("connectorId", connectorId, "resourceKind", request.resourceKind(),
                            "syncMode", mode, "upserts", counts.upserts(),
                            "deletes", counts.deletes(), "partial", counts.partial()));
            return repository.runs(tenantId, 100).stream()
                    .filter(run -> run.runId().equals(runId))
                    .findFirst().map(this::run).orElseThrow();
        } catch (MicrosoftGraphClient.GraphFailure failure) {
            Instant retryAt = failure.retryAfter() == null
                    ? null : Instant.now().plus(failure.retryAfter());
            StreamState streamState = failure.resetRequired()
                    ? StreamState.RESET_REQUIRED
                    : failure.health() == ConnectorHealth.AUTHENTICATION_REQUIRED
                            ? StreamState.AUTHENTICATION_REQUIRED : StreamState.STALE;
            repository.completeStream(
                    stream.streamId(), null, null, streamState, failure.code(), false);
            repository.completeRun(
                    runId, SyncRunState.FAILED, 0, 0, 0, 1,
                    false, retryAt, failure.code());
            repository.addRunError(
                    tenantId, runId, null, failure.code(), safeMessage(failure.code()),
                    failure.retryable());
            repository.recordConnectorFailure(tenantId, connectorId, failure.health(), failure.code());
            if (failure.health() == ConnectorHealth.AUTHENTICATION_REQUIRED) {
                repository.subjectFailure(
                        subject.subjectId(), ConsentState.REAUTHORIZATION_REQUIRED, failure.code());
            }
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, safeMessage(failure.code()));
        } catch (RuntimeException exception) {
            repository.completeStream(
                    stream.streamId(), null, null, StreamState.STALE,
                    "CONNECTOR_RUNTIME_FAILURE", false);
            repository.completeRun(
                    runId, SyncRunState.FAILED, 0, 0, 0, 1,
                    false, null, "CONNECTOR_RUNTIME_FAILURE");
            repository.addRunError(
                    tenantId, runId, null, "CONNECTOR_RUNTIME_FAILURE",
                    safeMessage("CONNECTOR_RUNTIME_FAILURE"), false);
            repository.recordConnectorFailure(
                    tenantId, connectorId, ConnectorHealth.DEGRADED, "CONNECTOR_RUNTIME_FAILURE");
            if (exception instanceof BaseException baseException) throw baseException;
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR, safeMessage("CONNECTOR_RUNTIME_FAILURE"));
        }
    }

    public ProductivityDtos.ItemPage items(
            Long tenantId,
            Long userId,
            ResourceKind resourceKind,
            int page,
            int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        ProductivityRepository.ItemResult result = repository.items(
                tenantId, userId, resourceKind, safePage, safeSize);
        List<ProductivityDtos.ProductivityItem> items = result.content().stream()
                .map(item -> new ProductivityDtos.ProductivityItem(
                        item.itemId(),
                        item.resourceKind(),
                        crypto.decrypt(item.encryptedTitle(), itemAad(item, "title")),
                        item.encryptedSourceUrl() == null ? null
                                : crypto.decrypt(item.encryptedSourceUrl(), itemAad(item, "url")),
                        item.occurredAt(),
                        item.endsAt(),
                        item.importance(),
                        item.readState(),
                        item.cancelled(),
                        item.classification()))
                .toList();
        return new ProductivityDtos.ItemPage(items, safePage, safeSize, result.total());
    }

    private SyncCounts synchronizePages(
            Long tenantId,
            Long userId,
            ProductivityRepository.ConnectorRecord connector,
            ProductivityRepository.SubjectRecord subject,
            ProductivityRepository.StreamRecord stream,
            ResourceKind resourceKind,
            String accessToken,
            String initialCursor,
            UUID runId) {
        int upserts = 0;
        int deletes = 0;
        int skips = 0;
        int errors = 0;
        String cursor = initialCursor;
        boolean more = false;
        for (int page = 0; page < maxPagesPerRun; page++) {
            MicrosoftGraphClient.GraphPage result = graph.readPage(
                    accessToken, resourceKind, cursor, stream.windowStart(), stream.windowEnd());
            for (MicrosoftGraphClient.GraphItem item : result.items()) {
                if (isBlank(item.sourceId())) {
                    skips++;
                    errors++;
                    repository.addRunError(
                            tenantId, runId, null, "GRAPH_ITEM_ID_MISSING",
                            safeMessage("GRAPH_ITEM_ID_MISSING"), false);
                    continue;
                }
                String sourceHash = crypto.fingerprint(item.sourceId());
                if (item.removed()) {
                    deletes += repository.tombstoneItem(
                            tenantId, userId, connector.connectorId(), resourceKind, sourceHash);
                    continue;
                }
                if (item.occurredAt() == null) {
                    skips++;
                    errors++;
                    repository.addRunError(
                            tenantId, runId, sourceHash, "GRAPH_ITEM_TIME_INVALID",
                            safeMessage("GRAPH_ITEM_TIME_INVALID"), false);
                    continue;
                }
                String sourceUrl = graph.trustedDeepLink(item.sourceUrl()) ? item.sourceUrl() : null;
                String baseAad = itemAad(
                        tenantId, userId, connector.connectorId(), resourceKind, sourceHash);
                repository.upsertItem(new ProductivityRepository.ItemRecord(
                        UUID.randomUUID(), tenantId, userId, connector.connectorId(), resourceKind,
                        sourceHash,
                        crypto.encrypt(item.title(), baseAad + ":title"),
                        sourceUrl == null ? null : crypto.encrypt(sourceUrl, baseAad + ":url"),
                        item.occurredAt(), item.endsAt(), item.importance(), item.read(),
                        item.cancelled(), "CONFIDENTIAL",
                        crypto.fingerprint("delegated:" + userId), item.sourceVersion()));
                upserts++;
            }
            cursor = result.nextCursor();
            more = result.hasMore();
            if (!more) break;
        }
        return new SyncCounts(upserts, deletes, skips, errors, more, cursor);
    }

    private ProductivityRepository.ConnectorDraft draft(ProductivityDtos.SaveConnectorRequest request) {
        List<String> scopes = request.requestedScopes().stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        return new ProductivityRepository.ConnectorDraft(
                request.connectorKey().trim().toUpperCase(Locale.ROOT),
                request.displayName().trim(),
                request.providerType(),
                request.authMode(),
                request.providerTenantId().trim(),
                request.clientId().trim(),
                request.credentialReference().trim(),
                request.redirectUri().trim(),
                scopes,
                CAPABILITIES,
                request.policyState());
    }

    private ConfigurationReadiness readiness(ProductivityRepository.ConnectorRecord connector) {
        if (!crypto.available()) return new ConfigurationReadiness(false, "DATA_ENCRYPTION_KEY_REQUIRED");
        if (credentialResolver.resolve(connector.credentialReference()).isEmpty()) {
            return new ConfigurationReadiness(false, "CLIENT_SECRET_REQUIRED");
        }
        if (!validUuid(connector.clientId())) return new ConfigurationReadiness(false, "CLIENT_ID_INVALID");
        if (!validRedirect(connector.redirectUri())) return new ConfigurationReadiness(false, "REDIRECT_URI_INVALID");
        if (!new LinkedHashSet<>(connector.requestedScopes()).containsAll(REQUIRED_SCOPES)
                || !connector.requestedScopes().stream().allMatch(ALLOWED_SCOPES::contains)) {
            return new ConfigurationReadiness(false, "SCOPE_POLICY_BLOCKED");
        }
        if (connector.policyState() != PolicyState.APPROVED) {
            return new ConfigurationReadiness(false, "POLICY_APPROVAL_REQUIRED");
        }
        return new ConfigurationReadiness(true, null);
    }

    private ProductivityRepository.ConnectorRecord requireConnector(Long tenantId, UUID connectorId) {
        return repository.connector(tenantId, connectorId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private ProductivityRepository.ConnectorRecord requireUsableConnector(
            Long tenantId,
            UUID connectorId) {
        ProductivityRepository.ConnectorRecord connector = requireConnector(tenantId, connectorId);
        if (connector.lifecycleState() != ConnectorLifecycle.ACTIVE) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The productivity connector is not active.");
        }
        return connector;
    }

    private ProductivityDtos.Connector connector(ProductivityRepository.ConnectorRecord value) {
        return new ProductivityDtos.Connector(
                value.connectorId(), value.connectorKey(), value.displayName(),
                value.providerType(), value.authMode(), value.providerTenantId(),
                value.clientId(), value.credentialReference(), value.redirectUri(),
                value.requestedScopes(), value.capabilities(), value.lifecycleState(),
                value.healthState(), value.policyState(), value.safeErrorCode(),
                value.lastConfigurationCheckAt(), value.lastSuccessfulSyncAt(),
                value.consecutiveFailures(), value.version());
    }

    private ProductivityDtos.Subject subject(ProductivityRepository.SubjectRecord value) {
        return new ProductivityDtos.Subject(
                value.subjectId(), value.connectorId(), value.userId(), value.consentState(),
                value.grantedScopes(), value.tokenExpiresAt(), value.lastSuccessfulSyncAt(),
                value.lastErrorCode());
    }

    private ProductivityDtos.SyncRun run(ProductivityRepository.RunRecord value) {
        return new ProductivityDtos.SyncRun(
                value.runId(), value.connectorId(), value.userId(), value.resourceKind(),
                value.syncMode(), value.runState(), value.startedAt(), value.completedAt(),
                value.upsertCount(), value.deleteCount(), value.skipCount(), value.errorCount(),
                value.partialResult(), value.retryAfterAt(), value.safeErrorCode(),
                value.correlationId());
    }

    private ProductivityDtos.Connection connection(
            ProductivityRepository.ConnectorRecord connector,
            ProductivityRepository.SubjectRecord subject) {
        ConsentState consent = subject == null ? ConsentState.NOT_CONNECTED : subject.consentState();
        String action = connector.lifecycleState() != ConnectorLifecycle.ACTIVE
                ? "CONNECTOR_NOT_ACTIVE"
                : connector.policyState() != PolicyState.APPROVED
                        ? "POLICY_APPROVAL_REQUIRED"
                        : consent == ConsentState.CONNECTED ? null : consent.name();
        return new ProductivityDtos.Connection(
                connector.connectorId(), connector.connectorKey(), connector.displayName(),
                connector.providerType(), connector.lifecycleState(), connector.healthState(),
                consent, connector.requestedScopes(),
                subject == null ? List.of() : subject.grantedScopes(),
                subject == null ? null : subject.lastSuccessfulSyncAt(), action);
    }

    private Map<String, Object> snapshot(ProductivityRepository.ConnectorRecord value) {
        return Map.ofEntries(
                Map.entry("connectorId", value.connectorId()),
                Map.entry("connectorKey", value.connectorKey()),
                Map.entry("providerType", value.providerType()),
                Map.entry("authMode", value.authMode()),
                Map.entry("lifecycleState", value.lifecycleState()),
                Map.entry("healthState", value.healthState()),
                Map.entry("policyState", value.policyState()),
                Map.entry("requestedScopes", value.requestedScopes()),
                Map.entry("credentialReferenceConfigured", !isBlank(value.credentialReference())),
                Map.entry("version", value.version()));
    }

    private void check(
            boolean success,
            String code,
            List<String> checks,
            List<String> blocking) {
        checks.add(code + ":" + (success ? "PASS" : "FAIL"));
        if (!success) blocking.add(code);
    }

    private static boolean validRedirect(String value) {
        if (isBlank(value)) return false;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getFragment() != null) return false;
            if ("https".equalsIgnoreCase(uri.getScheme())) return true;
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }

    private static String randomUrlValue(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String sha256Url(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static String oauthAad(Long tenantId, Long userId, String stateHash) {
        return "oauth:" + tenantId + ":" + userId + ":" + stateHash;
    }

    private static String subjectTokenAad(Long tenantId, Long userId, UUID connectorId) {
        return "subject-token:" + tenantId + ":" + userId + ":" + connectorId;
    }

    private static String cursorAad(
            Long tenantId,
            ProductivityRepository.SubjectRecord subject,
            ResourceKind resourceKind) {
        return "cursor:" + tenantId + ":" + subject.subjectId() + ":" + resourceKind;
    }

    private static String itemAad(ProductivityRepository.ItemRecord item, String field) {
        return itemAad(
                item.tenantId(), item.userId(), item.connectorId(),
                item.resourceKind(), item.sourceIdHash()) + ":" + field;
    }

    private static String itemAad(
            Long tenantId,
            Long userId,
            UUID connectorId,
            ResourceKind resourceKind,
            String sourceHash) {
        return "item:" + tenantId + ":" + userId + ":" + connectorId
                + ":" + resourceKind + ":" + sourceHash;
    }

    private static String safeMessage(String code) {
        return switch (code) {
            case "GRAPH_AUTHENTICATION_REQUIRED", "OAUTH_REQUEST_REJECTED" ->
                    "Microsoft 365 authorization must be renewed.";
            case "GRAPH_RATE_LIMITED" ->
                    "Microsoft Graph asked the connector to retry later.";
            case "GRAPH_CURSOR_RESET_REQUIRED" ->
                    "The provider delta cursor expired and requires a controlled reset.";
            case "GRAPH_UNAVAILABLE" ->
                    "Microsoft Graph is temporarily unavailable.";
            case "GRAPH_ITEM_ID_MISSING", "GRAPH_ITEM_TIME_INVALID" ->
                    "A provider item was skipped because required metadata was invalid.";
            default -> "The productivity connector could not complete this operation.";
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BaseException conflict() {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, "The connector changed. Refresh and retry.");
    }

    private record ConfigurationReadiness(boolean ready, String errorCode) {
    }

    private record SyncCounts(
            int upserts,
            int deletes,
            int skips,
            int errors,
            boolean partial,
            String cursor) {
    }
}
