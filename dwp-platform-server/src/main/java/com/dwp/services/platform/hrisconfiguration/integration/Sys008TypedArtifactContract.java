package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;
import com.dwp.platform.contracts.hris.generated.ConnectorMappingVersionV1;
import com.dwp.platform.contracts.hris.generated.ConnectorReconciliationReceiptV1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Narrows canonical PDX-007/008/009 to the sealed SYS-008 owner projection.
 * The canonical generated records remain the only wire types.
 */
public final class Sys008TypedArtifactContract {

    public static final long MAX_JSON_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final Map<String, String> MAPPING_STATE_PROJECTION = Map.ofEntries(
            Map.entry("DRAFT", "DRAFT"),
            Map.entry("VALIDATED", "VALIDATED"),
            Map.entry("PENDING_APPROVAL", "PENDING_APPROVAL"),
            Map.entry("APPROVED", "APPROVED"),
            Map.entry("ACTIVE", "ACTIVE"),
            Map.entry("SUSPENDED", "SUSPENDED"),
            Map.entry("RETIRED", "REVOKED"));

    private static final Map<String, String> EXECUTION_STATE_PROJECTION = Map.ofEntries(
            Map.entry("ACCEPTED", "ACCEPTED"),
            Map.entry("TRANSFORMING", "RUNNING"),
            Map.entry("DISPATCHED", "RUNNING"),
            Map.entry("RESULT_UNKNOWN", "RESULT_UNKNOWN"),
            Map.entry("ACKNOWLEDGED", "RUNNING"),
            Map.entry("RECONCILING", "RUNNING"),
            Map.entry("RECONCILED", "SUCCEEDED"),
            Map.entry("PARTIAL", "PARTIAL"),
            Map.entry("FAILED", "FAILED"),
            Map.entry("DEAD_LETTER", "FAILED"),
            Map.entry("CANCELLED", "CANCELED"));

    private Sys008TypedArtifactContract() {}

    public static ConnectorMappingVersionV1 requireInteroperable(
            ConnectorMappingVersionV1 mapping) {
        Objects.requireNonNull(mapping, "mapping must not be null");
        requireJsonSafePositive(mapping.version(), "mapping version");
        return mapping;
    }

    public static ConnectorExecutionRequestV1 requireInteroperable(
            ConnectorExecutionRequestV1 execution) {
        Objects.requireNonNull(execution, "execution must not be null");
        requireJsonSafePositive(execution.mappingVersion(), "mapping version");
        switch (execution.scopeType()) {
            case "TENANT" -> {
                if (execution.scopeId() != null) {
                    throw new IllegalArgumentException(
                            "TENANT scope must not carry scopeId");
                }
            }
            case "LEGAL_ENTITY" -> {
                if (execution.scopeId() == null) {
                    throw new IllegalArgumentException(
                            "LEGAL_ENTITY scope requires scopeId");
                }
            }
            default -> throw new IllegalArgumentException(
                    "SYS-008 supports only TENANT or LEGAL_ENTITY scope");
        }
        if ("DRY_RUN".equals(execution.mode())
                && (!"TENANT".equals(execution.scopeType())
                || execution.scopeId() != null)) {
            throw new IllegalArgumentException(
                    "DRY_RUN is restricted to tenant scope without scopeId");
        }
        return execution;
    }

    public static ConnectorReconciliationReceiptV1 requireInteroperable(
            ConnectorReconciliationReceiptV1 receipt) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        requireJsonSafePositive(receipt.mappingVersion(), "mapping version");
        return receipt;
    }

    public static ConnectorExecutionRequestV1 projectDryRun(
            OwnerIssuedExecutionContext ownerContext,
            PinnedMappingEnvelope pinnedMapping,
            VerifiedSourceEnvelope sampleEnvelope) {
        PinnedMappingEnvelope mapping = Objects.requireNonNull(
                pinnedMapping, "pinnedMapping must not be null");
        VerifiedSourceEnvelope sourceEnvelope = Objects.requireNonNull(sampleEnvelope,
                "sampleEnvelope must not be null");
        mapping.requireMatches(ownerContext);
        sourceEnvelope.requireIntegrity();
        if (!"TENANT".equals(sourceEnvelope.scope.scopeType)) {
            throw new IllegalArgumentException(
                    "dry-run source envelope must be tenant scoped");
        }
        return projected(ownerContext, "DRY_RUN", mapping.direction,
                sourceEnvelope);
    }

    public static ConnectorExecutionRequestV1 projectImportExecution(
            OwnerIssuedExecutionContext ownerContext,
            VerifiedSourceEnvelope sourceEnvelope) {
        return projected(ownerContext, "EXECUTE", "INBOUND", sourceEnvelope);
    }

    public static ConnectorExecutionRequestV1 projectExportExecution(
            OwnerIssuedExecutionContext ownerContext,
            VerifiedSourceEnvelope sourceEnvelope) {
        return projected(ownerContext, "EXECUTE", "OUTBOUND", sourceEnvelope);
    }

    public static ConnectorExecutionRequestV1 projectDispatchExecution(
            OwnerIssuedExecutionContext ownerContext,
            VerifiedSourceEnvelope sourceEnvelope) {
        return projected(ownerContext, "EXECUTE", "OUTBOUND", sourceEnvelope);
    }

    static PinnedMappingEnvelope pinInboundOwnerMapping(
            ConnectorMappingVersionV1 ownerMapping) {
        return new PinnedMappingEnvelope(ownerMapping, "INBOUND");
    }

    static PinnedMappingEnvelope pinOutboundOwnerMapping(
            ConnectorMappingVersionV1 ownerMapping) {
        return new PinnedMappingEnvelope(ownerMapping, "OUTBOUND");
    }

    static OwnerIssuedExecutionContext issueOwnerExecutionContext(
            UUID executionId,
            UUID connectorId,
            UUID mappingId,
            long mappingVersion,
            Instant requestedAt,
            String requestedByActorRef,
            String idempotencyKey,
            UUID correlationId) {
        return new OwnerIssuedExecutionContext(
                executionId, connectorId, mappingId, mappingVersion,
                requestedAt, requestedByActorRef, idempotencyKey, correlationId);
    }

    static CurrentPopulationContext issueCurrentPopulationContext(
            UUID connectorId,
            Set<UUID> legalEntityPublicIds,
            long authorizationRevision) {
        return new CurrentPopulationContext(
                connectorId, legalEntityPublicIds, authorizationRevision);
    }

    static FrozenSource freezeOwnerSource(String reference, String digest) {
        return new FrozenSource(reference, digest);
    }

    static VerifiedSourceEnvelope issueTenantSourceEnvelope(
            CurrentPopulationContext currentPopulation,
            FrozenSource source) {
        CurrentPopulationContext population = Objects.requireNonNull(
                currentPopulation, "currentPopulation must not be null");
        return trustedEnvelope(
                VerifiedScope.tenant(population.connectorId, population), source);
    }

    static VerifiedSourceEnvelope issueLegalEntitySourceEnvelope(
            CurrentPopulationContext currentPopulation,
            UUID legalEntityPublicId,
            FrozenSource source) {
        CurrentPopulationContext population = Objects.requireNonNull(
                currentPopulation, "currentPopulation must not be null");
        return trustedEnvelope(
                VerifiedScope.legalEntity(
                        population.connectorId, legalEntityPublicId, population),
                source);
    }

    public static ConnectorExecutionRequestV1 projectReconcile(
            OwnerIssuedExecutionContext ownerContext,
            ConnectorExecutionRequestV1 frozenOriginal,
            CurrentPopulationContext currentPopulation) {
        OwnerIssuedExecutionContext context = Objects.requireNonNull(
                ownerContext, "ownerContext must not be null");
        ConnectorExecutionRequestV1 original = requireInteroperable(frozenOriginal);
        if (!context.connectorId.equals(original.connectorId())
                || !context.mappingId.equals(original.mappingId())
                || context.mappingVersion != original.mappingVersion()) {
            throw new IllegalArgumentException(
                    "reconcile identity must retain the frozen connector mapping");
        }
        VerifiedScope inherited = "TENANT".equals(original.scopeType())
                ? VerifiedScope.tenant(original.connectorId(), currentPopulation)
                : VerifiedScope.legalEntity(
                        original.connectorId(), original.scopeId(), currentPopulation);
        return projected(context, "RECONCILE", original.direction(),
                trustedEnvelope(inherited,
                        new FrozenSource(
                                original.sourceRef(), original.sourceDigest())));
    }

    private static ConnectorExecutionRequestV1 projected(
            OwnerIssuedExecutionContext ownerContext,
            String mode,
            String direction,
            VerifiedSourceEnvelope sourceEnvelope) {
        OwnerIssuedExecutionContext context = Objects.requireNonNull(
                ownerContext, "ownerContext must not be null");
        VerifiedSourceEnvelope envelope = Objects.requireNonNull(sourceEnvelope,
                "sourceEnvelope must not be null");
        envelope.requireIntegrity();
        VerifiedScope verifiedScope = envelope.scope;
        FrozenSource source = envelope.source;
        if (!context.connectorId.equals(verifiedScope.connectorId)) {
            throw new IllegalArgumentException(
                    "verified connector population does not match execution");
        }
        return requireInteroperable(new ConnectorExecutionRequestV1(
                context.executionId, context.connectorId, context.mappingId,
                context.mappingVersion, mode, direction, verifiedScope.scopeType,
                verifiedScope.scopeId, source.reference, source.digest,
                context.requestedAt, context.requestedByActorRef,
                context.idempotencyKey, context.correlationId));
    }

    private static String envelopeBindingDigest(
            VerifiedScope scope, FrozenSource source) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(source, "source must not be null");
        String canonical = String.join("\u001f",
                scope.connectorId.toString(), scope.scopeType,
                scope.scopeId == null ? "" : scope.scopeId.toString(),
                source.reference, source.digest,
                Long.toString(scope.authorizationRevision));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static VerifiedSourceEnvelope trustedEnvelope(
            VerifiedScope scope, FrozenSource source) {
        return new VerifiedSourceEnvelope(
                scope, source, envelopeBindingDigest(scope, source));
    }

    public static String projectMappingState(String ownerState) {
        return requireProjection(MAPPING_STATE_PROJECTION, ownerState, "mapping");
    }

    public static String projectExecutionState(String ownerState) {
        return requireProjection(EXECUTION_STATE_PROJECTION, ownerState, "execution");
    }

    private static String requireProjection(
            Map<String, String> projection, String ownerState, String type) {
        String value = projection.get(ownerState);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Unsupported SYS-008 " + type + " owner state");
        }
        return value;
    }

    private static void requireJsonSafePositive(long value, String name) {
        if (value < 1L || value > MAX_JSON_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    name + " must be a positive JSON-safe integer");
        }
    }

    /** Opaque execution facts issued only by the package-local owner adapter seam. */
    public static final class OwnerIssuedExecutionContext {
        private final UUID executionId;
        private final UUID connectorId;
        private final UUID mappingId;
        private final long mappingVersion;
        private final Instant requestedAt;
        private final String requestedByActorRef;
        private final String idempotencyKey;
        private final UUID correlationId;

        private OwnerIssuedExecutionContext(
                UUID executionId,
                UUID connectorId,
                UUID mappingId,
                long mappingVersion,
                Instant requestedAt,
                String requestedByActorRef,
                String idempotencyKey,
                UUID correlationId) {
            this.executionId = Objects.requireNonNull(
                    executionId, "executionId must not be null");
            this.connectorId = Objects.requireNonNull(
                    connectorId, "connectorId must not be null");
            this.mappingId = Objects.requireNonNull(
                    mappingId, "mappingId must not be null");
            requireJsonSafePositive(mappingVersion, "mapping version");
            this.mappingVersion = mappingVersion;
            this.requestedAt = Objects.requireNonNull(
                    requestedAt, "requestedAt must not be null");
            this.requestedByActorRef = Objects.requireNonNull(
                    requestedByActorRef, "requestedByActorRef must not be null");
            this.idempotencyKey = Objects.requireNonNull(
                    idempotencyKey, "idempotencyKey must not be null");
            this.correlationId = Objects.requireNonNull(
                    correlationId, "correlationId must not be null");
        }
    }

    /**
     * Opaque proof that a direction is attached to one immutable owner mapping version.
     * Only the package-local owner adapter seam can select an owner mapping direction. Public
     * projection callers cannot construct this token or redeclare a raw PDX-008 direction.
     */
    public static final class PinnedMappingEnvelope {
        private final UUID connectorId;
        private final UUID mappingId;
        private final long mappingVersion;
        private final String mappingDigest;
        private final String direction;

        private PinnedMappingEnvelope(
                ConnectorMappingVersionV1 ownerMapping,
                String direction) {
            ConnectorMappingVersionV1 mapping = requireInteroperable(ownerMapping);
            this.connectorId = mapping.connectorId();
            this.mappingId = mapping.mappingId();
            this.mappingVersion = mapping.version();
            this.mappingDigest = mapping.mappingDigest();
            this.direction = direction;
        }

        private void requireMatches(OwnerIssuedExecutionContext ownerContext) {
            OwnerIssuedExecutionContext context = Objects.requireNonNull(
                    ownerContext, "ownerContext must not be null");
            if (!connectorId.equals(context.connectorId)
                    || !mappingId.equals(context.mappingId)
                    || mappingVersion != context.mappingVersion) {
                throw new IllegalArgumentException(
                        "dry-run identity must retain the pinned owner mapping");
            }
            if (mappingDigest == null || mappingDigest.isBlank()) {
                throw new IllegalArgumentException(
                        "pinned owner mapping digest is required");
            }
        }
    }

    static final class FrozenSource {
        private final String reference;
        private final String digest;

        private FrozenSource(String reference, String digest) {
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("source reference is required");
            }
            if (digest == null || !digest.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException(
                        "source digest must be a lowercase SHA-256 digest");
            }
            this.reference = reference;
            this.digest = digest;
        }
    }

    /** Opaque proof of the owner's current connector and legal-entity population. */
    public static final class CurrentPopulationContext {
        private final UUID connectorId;
        private final Set<UUID> legalEntityPublicIds;
        private final long authorizationRevision;

        private CurrentPopulationContext(
                UUID connectorId,
                Set<UUID> legalEntityPublicIds,
                long authorizationRevision) {
            this.connectorId = Objects.requireNonNull(
                    connectorId, "connectorId must not be null");
            this.legalEntityPublicIds = Set.copyOf(Objects.requireNonNull(
                    legalEntityPublicIds, "legalEntityPublicIds must not be null"));
            requireJsonSafePositive(authorizationRevision,
                    "authorization revision");
            this.authorizationRevision = authorizationRevision;
        }
    }

    /** Opaque source and scope proof issued only by the package-local owner adapter seam. */
    public static final class VerifiedSourceEnvelope {
        private final VerifiedScope scope;
        private final FrozenSource source;
        private final String bindingDigest;

        private VerifiedSourceEnvelope(
                VerifiedScope scope, FrozenSource source, String bindingDigest) {
            this.scope = Objects.requireNonNull(scope);
            this.source = Objects.requireNonNull(source);
            this.bindingDigest = Objects.requireNonNull(bindingDigest);
        }

        private void requireIntegrity() {
            if (!Objects.equals(bindingDigest,
                    envelopeBindingDigest(scope, source))) {
                throw new IllegalArgumentException(
                        "source envelope is not bound to the verified scope");
            }
        }
    }

    private static final class VerifiedScope {
        private final UUID connectorId;
        private final String scopeType;
        private final UUID scopeId;
        private final long authorizationRevision;

        private VerifiedScope(
                UUID connectorId,
                String scopeType,
                UUID scopeId,
                long authorizationRevision) {
            this.connectorId = Objects.requireNonNull(
                    connectorId, "connectorId must not be null");
            this.scopeType = scopeType;
            this.scopeId = scopeId;
            this.authorizationRevision = authorizationRevision;
            requireJsonSafePositive(authorizationRevision,
                    "authorization revision");
            if (!("TENANT".equals(scopeType) && scopeId == null)
                    && !("LEGAL_ENTITY".equals(scopeType) && scopeId != null)) {
                throw new IllegalArgumentException(
                        "verified scope must be TENANT or LEGAL_ENTITY");
            }
        }

        private static VerifiedScope tenant(
                UUID connectorId, CurrentPopulationContext currentPopulation) {
            CurrentPopulationContext verified = requireAuthority(
                    connectorId, currentPopulation);
            return new VerifiedScope(connectorId, "TENANT", null,
                    verified.authorizationRevision);
        }

        private static VerifiedScope legalEntity(
                UUID connectorId,
                UUID legalEntityPublicId,
                CurrentPopulationContext currentPopulation) {
            UUID legalEntity = Objects.requireNonNull(
                    legalEntityPublicId, "legalEntityPublicId must not be null");
            CurrentPopulationContext verified = requireAuthority(
                    connectorId, currentPopulation);
            if (!verified.legalEntityPublicIds.contains(legalEntity)) {
                throw new IllegalArgumentException(
                        "legal entity is outside the verified population");
            }
            return new VerifiedScope(connectorId, "LEGAL_ENTITY", legalEntity,
                    verified.authorizationRevision);
        }

        private static CurrentPopulationContext requireAuthority(
                UUID connectorId, CurrentPopulationContext currentPopulation) {
            CurrentPopulationContext verified = Objects.requireNonNull(
                    currentPopulation, "currentPopulation must not be null");
            if (!Objects.equals(connectorId, verified.connectorId)) {
                throw new IllegalArgumentException(
                        "connector is outside the verified population");
            }
            return verified;
        }
    }
}
