package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.ConnectorMappingVersionV1;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.MappingRecord;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;

/** Shared constants, internal evidence and pure command rules. */
final class IntegrationServiceInternals {

    static final Duration EFFECT_CLAIM_RECOVERY_AFTER =
            Duration.ofMinutes(5);
    static final java.util.Set<String> CATALOG_FIELDS = java.util.Set.of(
            "publicId", "key", "type", "state", "version", "updatedAt");

    static final String CREATE_CONNECTOR =
            "hris.action.sys.post.platform.v1.admin.hris.connectors";
    static final String TEST_CONNECTOR = CREATE_CONNECTOR + ".resource.test";
    static final String DRY_RUN_MAPPING =
            CREATE_CONNECTOR + ".resource.mappings.resource.dry-runs";
    static final String CREATE_EXECUTION = CREATE_CONNECTOR + ".resource.executions";
    static final String VALIDATE_DEFINITION = CREATE_CONNECTOR + ".resource.validate";
    static final String SUBMIT_DEFINITION = CREATE_CONNECTOR + ".resource.submit";
    static final String APPROVE_DEFINITION = CREATE_CONNECTOR + ".resource.approve";
    static final String ACTIVATE_DEFINITION = CREATE_CONNECTOR + ".resource.activate";
    static final String CREATE_MAPPING = CREATE_CONNECTOR + ".resource.mappings";
    static final String VALIDATE_MAPPING = CREATE_MAPPING + ".resource.validate";
    static final String SUBMIT_MAPPING = CREATE_MAPPING + ".resource.submit";
    static final String APPROVE_MAPPING = CREATE_MAPPING + ".resource.approve";
    static final String ACTIVATE_MAPPING = CREATE_MAPPING + ".resource.activate";
    static final String RECONCILE_EXECUTION =
            "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.reconcile";
    static final String RETRY_EXECUTION =
            "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.retry";
    static final String CANCEL_EXECUTION =
            "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.cancel";

    static ConnectorMappingVersionV1 mappingArtifact(
            MappingRecord mapping) {
        return Sys008TypedArtifactContract.requireInteroperable(
                new ConnectorMappingVersionV1(
                        mapping.connectorId(), mapping.mappingId(),
                        mapping.version(),
                        Sys008TypedArtifactContract.projectMappingState(
                                mapping.state().name()),
                        "OwnerSource.v" + mapping.sourceSchemaVersion(),
                        "ProviderTarget.v" + mapping.targetSchemaVersion(),
                        mapping.mappingDigest(), mapping.dryRunResultDigest(),
                        "actor:" + mapping.authorActorId(),
                        mapping.approvedByActorId() == null ? null
                                : "actor:" + mapping.approvedByActorId(),
                        mapping.approvalReceiptId(),
                        mapping.effectiveFrom().toLocalDate(),
                        mapping.effectiveTo() == null ? null
                                : mapping.effectiveTo().toLocalDate(),
                        mapping.compatibilityMode().name()));
    }

    static void requireUnchanged(
            DefinitionRecord expected, DefinitionRecord current) {
        if (!expected.definitionVersionId().equals(current.definitionVersionId())
                || expected.aggregateRevision() != current.aggregateRevision()
                || expected.objectRevision() != current.objectRevision()
                || expected.state() != current.state()
                || !constant(expected.definitionDigest(),
                        current.definitionDigest())) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "SYS008_DEFINITION_CHANGED_DURING_EXTERNAL_EFFECT");
        }
    }

    static void requireUnchanged(
            MappingRecord expected, MappingRecord current) {
        if (!expected.mappingId().equals(current.mappingId())
                || expected.objectRevision() != current.objectRevision()
                || expected.state() != current.state()
                || !constant(expected.mappingDigest(), current.mappingDigest())) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "SYS008_MAPPING_CHANGED_DURING_EXTERNAL_EFFECT");
        }
    }

    static void requireTrustedAuthority(Authority authority) {
        if (authority == null || !IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE
                .equals(authority.purposeCode())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_OWNER_AUTHORITY_REQUIRED");
        }
    }

    static void requireAuthor(Authority authority) {
        if (!authority.author()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_AUTHOR_AUTHORITY_REQUIRED");
        }
    }

    static void requireExecutor(Authority authority) {
        if (!authority.executor()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_EXECUTOR_AUTHORITY_REQUIRED");
        }
    }

    static void requireStepUp(Authority authority) {
        if (authority.stepUpEvidenceDigest() == null) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "SYS008_TARGET_BOUND_STEP_UP_REQUIRED");
        }
    }

    static void requireRevision(long current, Authority authority) {
        if (authority.expectedObjectRevision() == null
                || authority.expectedObjectRevision() != current) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "SYS008_EXPECTED_REVISION_CONFLICT");
        }
    }

    static String actionForRoute(String route) {
        return switch (route) {
            case "route.hcm.configuration.connector-definition-create.action" -> CREATE_CONNECTOR;
            case "route.hcm.configuration.connector-test.action" -> TEST_CONNECTOR;
            case "route.hcm.configuration.connector-mapping-dry-run.action" -> DRY_RUN_MAPPING;
            case "route.hcm.configuration.connector-execution-create.action" -> CREATE_EXECUTION;
            case "route.hcm.configuration.connector-definition-validate.action" -> VALIDATE_DEFINITION;
            case "route.hcm.configuration.connector-definition-submit.action" -> SUBMIT_DEFINITION;
            case "route.hcm.configuration.connector-definition-approve.action" -> APPROVE_DEFINITION;
            case "route.hcm.configuration.connector-definition-activate.action" -> ACTIVATE_DEFINITION;
            case "route.hcm.configuration.connector-mapping-version-create.action" -> CREATE_MAPPING;
            case "route.hcm.configuration.connector-mapping-version-validate.action" -> VALIDATE_MAPPING;
            case "route.hcm.configuration.connector-mapping-version-submit.action" -> SUBMIT_MAPPING;
            case "route.hcm.configuration.connector-mapping-version-approve.action" -> APPROVE_MAPPING;
            case "route.hcm.configuration.connector-mapping-version-activate.action" -> ACTIVATE_MAPPING;
            case "route.hcm.configuration.connector-execution-reconcile.action" -> RECONCILE_EXECUTION;
            case "route.hcm.configuration.connector-execution-retry.action" -> RETRY_EXECUTION;
            case "route.hcm.configuration.connector-execution-cancel.action" -> CANCEL_EXECUTION;
            default -> "";
        };
    }

    static long activeMappingVersion(DefinitionRecord definition) {
        if (definition.activeMappingVersion() < 1) {
            throw conflict("SYS008_ACTIVE_MAPPING_NOT_AVAILABLE");
        }
        return definition.activeMappingVersion();
    }

    static UUID requireUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw invalid("SYS008_CURSOR_INVALID");
        }
    }

    static boolean constant(String left, String right) {
        return left != null && right != null && java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String definitionTarget(UUID id, Authority authority) {
        return id + ":revision:" + String.valueOf(
                authority.expectedObjectRevision());
    }

    static String mappingTarget(
            UUID id, long version, Authority authority) {
        return id + ":" + version + ":revision:"
                + String.valueOf(authority.expectedObjectRevision());
    }

    static String connectorRef(UUID id) {
        return "/v1/admin/hris/connectors/" + id;
    }

    static String mappingRef(UUID id, long version) {
        return connectorRef(id) + "/mappings/" + version;
    }

    static String executionRef(UUID id) {
        return "/v1/admin/hris/connector-executions/" + id;
    }

    static URI canonicalEndpoint(
            URI endpoint,
            Sys008EgressBoundary.Allowlist allowlist,
            List<java.net.InetAddress> destinations) {
        if (endpoint == null || endpoint.isOpaque() || !endpoint.isAbsolute()
                || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || endpoint.getScheme() == null || endpoint.getHost() == null) {
            throw invalid("SYS008_EGRESS_ENDPOINT_INVALID");
        }
        try {
            URI canonicalEndpoint = new URI(
                    endpoint.getScheme().toLowerCase(java.util.Locale.ROOT),
                    null,
                    endpoint.getHost().toLowerCase(java.util.Locale.ROOT),
                    endpoint.getPort(),
                    endpoint.getRawPath() == null
                            || endpoint.getRawPath().isBlank()
                            ? null : endpoint.getRawPath(),
                    null,
                    null).normalize();
            Sys008EgressBoundary.requireAllowed(
                    canonicalEndpoint, allowlist, destinations);
            return canonicalEndpoint;
        } catch (java.net.URISyntaxException | IllegalArgumentException invalid) {
            throw invalid("SYS008_EGRESS_ENDPOINT_INVALID");
        }
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    static BaseException notFound(String message) {
        return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, message);
    }

    record ReservedCommand(
            CommandSeal seal,
            CommandReceipt receipt,
            OffsetDateTime now,
            boolean owner) {}

    record DefinitionEffectClaim(
            ReservedCommand reserved,
            DefinitionRecord current,
            CommandReceipt replayReceipt,
            boolean replay) {

        static DefinitionEffectClaim replay(CommandReceipt receipt) {
            return new DefinitionEffectClaim(null, null, receipt, true);
        }

        CommandReceipt receipt() {
            return replay ? replayReceipt : reserved.receipt();
        }
    }

    record MappingEffectClaim(
            ReservedCommand reserved,
            MappingRecord current,
            CommandReceipt replayReceipt,
            boolean replay) {

        static MappingEffectClaim replay(CommandReceipt receipt) {
            return new MappingEffectClaim(null, null, receipt, true);
        }

        CommandReceipt receipt() {
            return replay ? replayReceipt : reserved.receipt();
        }
    }
}
