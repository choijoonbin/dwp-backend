package com.dwp.services.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthOpenApiSchemaOwnershipContractTest {

    private static final String COMPONENT = "#/components/schemas/";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void internalAuthorityOperationIdsAndResponseOwnersRemainStable() throws IOException {
        JsonNode auth = contract("auth.json");

        assertOperationId(auth,
                "/internal/auth/v1/meeting-followup-authority/evaluate", "evaluate");
        assertOperationId(auth,
                "/internal/approval-workflow/information-command-replay", "evaluate_1");
        assertOperationId(auth,
                "/internal/auth/v2/workforce-policy-governance/evaluate",
                "evaluateWorkforcePolicyGovernanceAuthorityV1");

        assertThat(postResponseSchema(auth,
                "/internal/approval-workflow/runtime-authority", "application/json")
                .path("$ref").asText()).isEqualTo(COMPONENT + "Response");
        assertProperties(auth, "Response", "attestation");

        assertThat(postResponseSchema(auth,
                "/internal/auth/v2/workforce-policy-governance/evaluate", "*/*")
                .path("$ref").asText())
                .isEqualTo(COMPONENT + "WorkforcePolicyGovernanceResponse");
        assertThat(refs(schema(auth, "WorkforcePolicyGovernanceResponse").path("oneOf")))
                .containsExactly(
                        COMPONENT + "WorkforcePolicyGovernanceSuccess",
                        COMPONENT + "WorkforcePolicyGovernanceFailure");
        assertProperties(auth, "WorkforcePolicyGovernanceSuccess",
                "schemaVersion", "kind", "boundary", "result", "evidenceId",
                "requestNonce", "operationId", "requestDigest", "candidateDigest",
                "capturedAt", "issuedAt", "expiresAt", "actor", "tenant", "session",
                "scope", "authPolicy", "subject", "peopleOwnerValidation", "ownerService",
                "audience", "purpose");
        assertProperties(auth, "WorkforcePolicyGovernanceFailure",
                "schemaVersion", "kind", "boundary", "result", "requestNonce",
                "operationId", "code");
        assertThat(values(schema(auth, "WorkforcePolicyGovernanceFailure")
                .path("properties").path("operationId").path("enum")))
                .containsExactly(
                        "POLICY_LIST_AUTH_READ", "ORGANIZATION_OPTIONS_AUTH_READ",
                        "POLICY_CREATE_PREFLIGHT_AUTH_READ",
                        "POLICY_REVOKE_PREFLIGHT_AUTH_READ");
        assertThat(values(schema(auth, "WorkforcePolicyGovernanceFailure")
                .path("properties").path("code").path("enum")))
                .contains("MALFORMED_REQUEST", "PERMISSION_DENIED",
                        "OWNER_SOURCE_UNAVAILABLE");
        assertThat(auth.path("components").path("schemas").has("Success")).isFalse();
        assertThat(auth.path("components").path("schemas").has("Failure")).isFalse();
    }

    @Test
    void publicRequestCollisionsKeepGenericWinnersAndOwnerQualifiedVariants()
            throws IOException {
        JsonNode auth = contract("auth.json");
        JsonNode gateway = contract("gateway-public.json");

        assertRequestRef(auth, "/auth/admin/access/app-governance/assignments", "post",
                COMPONENT + "AppGovernanceCreateAssignmentRequest");
        assertRequestRef(gateway, "/api/auth/admin/access/app-governance/assignments", "post",
                COMPONENT + "auth_AppGovernanceCreateAssignmentRequest");
        assertRequestRef(auth, "/auth/admin/tenant-app-adoption/assignments", "post",
                COMPONENT + "CreateAssignmentRequest");
        assertRequestRef(gateway, "/api/auth/admin/tenant-app-adoption/assignments", "post",
                COMPONENT + "auth_CreateAssignmentRequest");
        assertProperties(auth, "AppGovernanceCreateAssignmentRequest",
                "principalType", "principalRef", "responsibilityCode", "resourceSetId",
                "validTo", "justification");
        assertRequired(auth, "AppGovernanceCreateAssignmentRequest",
                "justification", "principalRef", "principalType", "resourceSetId",
                "responsibilityCode");
        assertThat(schema(auth, "AppGovernanceCreateAssignmentRequest")
                .path("properties").path("principalType").path("pattern").asText())
                .isEqualTo("USER|GROUP");
        assertProperties(auth, "CreateAssignmentRequest",
                "installationId", "userId", "validTo", "justification");
        assertRequired(auth, "CreateAssignmentRequest",
                "installationId", "justification", "userId");

        assertRequestRef(auth,
                "/auth/admin/tenant-app-adoption/installations/{installationId}/activate",
                "post", COMPONENT + "TenantAppAdoptionActivationCommand");
        assertRequestRef(gateway,
                "/api/auth/admin/tenant-app-adoption/installations/{installationId}/activate",
                "post", COMPONENT + "auth_TenantAppAdoptionActivationCommand");
        assertRequestRef(auth,
                "/internal/auth/v1/product-authorization/operations/bundles/{bundleKey}"
                        + "/versions/{version}/activation",
                "post", COMPONENT + "ActivationCommand");
        assertProperties(auth, "TenantAppAdoptionActivationCommand", "version", "reason");
        assertRequired(auth, "TenantAppAdoptionActivationCommand", "reason", "version");
        assertProperties(auth, "ActivationCommand",
                "activatedBy", "changeRef", "checksum", "expectedRevision");
        assertRequired(auth, "ActivationCommand",
                "activatedBy", "changeRef", "checksum", "expectedRevision");
        assertThat(schema(auth, "ActivationCommand").path("properties")
                .path("checksum").path("pattern").asText()).isEqualTo("^[0-9a-f]{64}$");

        assertRequestRef(auth,
                "/auth/admin/provisioning/scim/connectors/{connectorId}/lifecycle", "patch",
                COMPONENT + "ScimConnectorLifecycleRequest");
        assertRequestRef(gateway,
                "/api/auth/admin/provisioning/scim/connectors/{connectorId}/lifecycle", "patch",
                COMPONENT + "auth_ScimConnectorLifecycleRequest");
        assertRequestRef(auth,
                "/auth/admin/directory/groups/{groupId}/activate", "post",
                COMPONENT + "LifecycleRequest");
        assertProperties(auth, "ScimConnectorLifecycleRequest", "state");
        assertRequired(auth, "ScimConnectorLifecycleRequest", "state");
        assertThat(schema(auth, "ScimConnectorLifecycleRequest").path("properties")
                .path("state").path("pattern").asText())
                .isEqualTo("ACTIVE|SUSPENDED|RETIRED");
        assertProperties(auth, "LifecycleRequest", "version");
        assertRequired(auth, "LifecycleRequest", "version");
    }

    @Test
    void publicResponseCollisionsUseExactOwnerQualifiedWrapperGraphs()
            throws IOException {
        JsonNode auth = contract("auth.json");
        JsonNode gateway = contract("gateway-public.json");

        assertResponseRef(auth,
                "/auth/admin/tenant-app-adoption/capability-overrides", "post",
                COMPONENT + "ApiResponseTenantCapabilityOverrideChange");
        assertResponseRef(gateway,
                "/api/auth/admin/tenant-app-adoption/capability-overrides", "post",
                COMPONENT + "auth_ApiResponseTenantCapabilityOverrideChange");
        assertRef(auth, "ApiResponseTenantCapabilityOverrideChange", "data",
                COMPONENT + "TenantCapabilityOverrideChange");
        assertRef(gateway, "auth_ApiResponseTenantCapabilityOverrideChange", "data",
                COMPONENT + "auth_TenantCapabilityOverrideChange");
        assertRef(auth, "ApiResponseChange", "data", COMPONENT + "Change");
        assertProperties(auth, "TenantCapabilityOverrideChange",
                "overrideChangeId", "contractKey", "productKey", "appResourceKey",
                "policyRuleKey", "policyRuleVersion", "baseBundleId", "baseActiveRevision",
                "desiredState", "lifecycleState", "validTo", "justification", "requestedBy",
                "submittedAt", "approvedBy", "approvedAt", "decisionReason", "activatedBy",
                "activatedAt", "activationReceiptId", "revokedBy", "revokedAt",
                "revocationReason", "version", "createdAt", "updatedAt", "allowedActions");
        assertProperties(auth, "Change",
                "changeId", "settingKey", "ownerKey", "ownerVersion", "beforeValue",
                "proposedValue", "desiredState", "lifecycleState", "justification",
                "requestedBy", "submittedAt", "approvedBy", "approvedAt", "decisionReason",
                "publishedBy", "publishedAt", "publishReceiptId", "version", "createdAt",
                "updatedAt", "impactCount", "impactCoverage", "impactObservedAt", "preview",
                "allowedActions");

        assertResponseRef(auth, "/auth/tenant-settings/effective-settings/me", "get",
                COMPONENT + "ApiResponseListTenantSettingsEffectiveSetting");
        assertResponseRef(gateway, "/api/auth/tenant-settings/effective-settings/me", "get",
                COMPONENT + "auth_ApiResponseListTenantSettingsEffectiveSetting");
        assertArrayItemRef(auth, "ApiResponseListTenantSettingsEffectiveSetting", "data",
                COMPONENT + "TenantSettingsEffectiveSetting");
        assertArrayItemRef(gateway, "auth_ApiResponseListTenantSettingsEffectiveSetting", "data",
                COMPONENT + "auth_TenantSettingsEffectiveSetting");
        assertArrayItemRef(auth, "ApiResponseListEffectiveSetting", "data",
                COMPONENT + "EffectiveSetting");
        assertProperties(auth, "TenantSettingsEffectiveSetting",
                "settingKey", "effectiveValue", "resolutionStrategy", "effectiveSource",
                "locked", "overrideAllowed", "overrideState", "sources", "evaluatedAt",
                "evidenceState");
        assertProperties(auth, "EffectiveSetting",
                "settingKey", "effectiveValue", "effectiveSource", "overrideState",
                "provenance", "evaluatedAt", "sourceUpdatedAt", "freshnessState",
                "localizedLabelKey");

        assertResponseRef(auth, "/auth/admin/identity/roles", "get",
                COMPONENT + "ApiResponseListIdentityAdminRoleSummary");
        assertResponseRef(gateway, "/api/auth/admin/identity/roles", "get",
                COMPONENT + "auth_ApiResponseListIdentityAdminRoleSummary");
        assertArrayItemRef(auth, "ApiResponseListIdentityAdminRoleSummary", "data",
                COMPONENT + "IdentityAdminRoleSummary");
        assertArrayItemRef(gateway, "auth_ApiResponseListIdentityAdminRoleSummary", "data",
                COMPONENT + "auth_IdentityAdminRoleSummary");
        assertArrayItemRef(auth, "ApiResponseListRoleSummary", "data",
                COMPONENT + "RoleSummary");
        assertProperties(auth, "IdentityAdminRoleSummary",
                "code", "name", "description", "roleFamily", "assignmentClass",
                "privileged", "assignmentMode", "conflictsWith", "status");
        assertProperties(auth, "RoleSummary",
                "roleId", "code", "name", "description", "roleType", "status",
                "privileged", "assignableToGroups", "permissions", "version");
    }

    @Test
    void internalIdentitySchemasKeepSpaceGenericAndSplitAppAndWorkforceOwners()
            throws IOException {
        JsonNode auth = contract("auth.json");
        JsonNode gateway = contract("gateway-public.json");

        assertRequestRef(auth,
                "/internal/identity/v1/tenants/{tenantId}/app-entitlements/{sourceRef}", "put",
                COMPONENT + "AppEntitlementSyncRequest");
        assertRequestRef(auth,
                "/internal/identity/v1/tenants/{tenantId}/space-entitlements/{sourceRef}", "put",
                COMPONENT + "SyncRequest");
        assertResponseRef(auth,
                "/internal/identity/v1/tenants/{tenantId}/app-entitlements/{sourceRef}", "put",
                COMPONENT + "SyncResult");
        assertResponseRef(auth,
                "/internal/identity/v1/tenants/{tenantId}/space-entitlements/{sourceRef}", "put",
                COMPONENT + "SyncResult");
        assertResponseRef(auth, "/internal/identity/v1/workforce-events", "post",
                COMPONENT + "WorkforceIdentitySyncResult");

        assertProperties(auth, "AppEntitlementSyncRequest",
                "principalType", "principalRef", "resourceKey", "permissionCode", "action",
                "validTo", "actorId", "justification");
        assertRequired(auth, "AppEntitlementSyncRequest",
                "action", "actorId", "justification", "permissionCode", "principalRef",
                "principalType", "resourceKey");
        assertThat(schema(auth, "AppEntitlementSyncRequest").path("properties")
                .path("permissionCode").path("pattern").asText())
                .isEqualTo("[A-Z][A-Z0-9_.-]{0,49}");
        assertProperties(auth, "SyncRequest",
                "principalType", "principalRef", "resourceKey", "resourceName",
                "permissionCode", "action", "validTo", "justification", "actorId");
        assertRequired(auth, "SyncRequest",
                "action", "actorId", "justification", "permissionCode", "principalRef",
                "principalType", "resourceKey", "resourceName");
        assertThat(schema(auth, "SyncRequest").path("properties")
                .path("resourceKey").path("pattern").asText())
                .isEqualTo("SPACE\\.[A-Z0-9][A-Z0-9._:-]{1,249}");
        assertProperties(auth, "SyncResult",
                "grantId", "tenantId", "principalType", "principalRef", "resourceKey",
                "permissionCode", "sourceType", "sourceRef", "lifecycleState", "validFrom",
                "validTo", "version", "changed");
        assertProperties(auth, "WorkforceIdentitySyncResult",
                "eventId", "tenantId", "userId", "lifecycleState", "replayed");
        assertThat(gateway.path("components").path("schemas")
                .has("auth_AppEntitlementSyncRequest")).isFalse();
        assertThat(gateway.path("components").path("schemas")
                .has("auth_WorkforceIdentitySyncResult")).isFalse();
    }

    private void assertOperationId(JsonNode document, String path, String operationId) {
        assertThat(document.path("paths").path(path).path("post")
                .path("operationId").asText()).isEqualTo(operationId);
    }

    private void assertRequestRef(
            JsonNode document, String path, String method, String expected) {
        assertThat(document.path("paths").path(path).path(method).path("requestBody")
                .path("content").path("application/json").path("schema")
                .path("$ref").asText()).isEqualTo(expected);
    }

    private void assertResponseRef(
            JsonNode document, String path, String method, String expected) {
        assertThat(responseSchema(document, path, method).path("$ref").asText())
                .isEqualTo(expected);
    }

    private JsonNode postResponseSchema(JsonNode document, String path, String mediaType) {
        return document.path("paths").path(path).path("post").path("responses").path("200")
                .path("content").path(mediaType).path("schema");
    }

    private JsonNode responseSchema(JsonNode document, String path, String method) {
        JsonNode content = document.path("paths").path(path).path(method)
                .path("responses").path("200").path("content");
        return content.elements().next().path("schema");
    }

    private void assertRef(
            JsonNode document, String wrapper, String property, String expected) {
        assertThat(schema(document, wrapper).path("properties").path(property)
                .path("$ref").asText()).isEqualTo(expected);
    }

    private void assertArrayItemRef(
            JsonNode document, String wrapper, String property, String expected) {
        assertThat(schema(document, wrapper).path("properties").path(property)
                .path("items").path("$ref").asText()).isEqualTo(expected);
    }

    private void assertProperties(JsonNode document, String name, String... expected) {
        assertThat(fieldNames(schema(document, name).path("properties")))
                .containsExactlyInAnyOrder(expected);
    }

    private void assertRequired(JsonNode document, String name, String... expected) {
        assertThat(values(schema(document, name).path("required")))
                .containsExactlyInAnyOrder(expected);
    }

    private JsonNode schema(JsonNode document, String name) {
        JsonNode value = document.path("components").path("schemas").path(name);
        assertThat(value.isMissingNode()).as(name).isFalse();
        return value;
    }

    private Set<String> fieldNames(JsonNode object) {
        Set<String> result = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private Set<String> values(JsonNode array) {
        Set<String> result = new LinkedHashSet<>();
        array.forEach(value -> result.add(value.asText()));
        return result;
    }

    private Set<String> refs(JsonNode array) {
        Set<String> result = new LinkedHashSet<>();
        array.forEach(value -> result.add(value.path("$ref").asText()));
        return result;
    }

    private JsonNode contract(String fileName) throws IOException {
        return json.readTree(repositoryRoot().resolve("contracts/openapi")
                .resolve(fileName).toFile());
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("contracts/openapi/auth.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root was not found.");
    }
}
