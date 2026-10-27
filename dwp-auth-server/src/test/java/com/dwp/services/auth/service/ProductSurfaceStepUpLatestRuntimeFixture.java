package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static com.dwp.services.auth.service.ProductSurfaceStepUpLatestRuntimeTest.*;

/** Requests, signatures and scoped identity setup for the latest sealed runtime tests. */
final class ProductSurfaceStepUpLatestRuntimeFixture {
    private ProductSurfaceStepUpLatestRuntimeFixture() {
    }

    static ProductSurfaceAuthorityDtos.AuthorityResult currentAuthority() {
        return currentAuthority(request(null, null), ROUTE);
    }

    static ProductSurfaceStepUpDtos.IssueRequest payrollPrepareRequest() {
        return new ProductSurfaceStepUpDtos.IssueRequest(
                "POST", "/api/payroll/v1/runs", "opaque-context", "PAY_GROUP_49",
                "PAYROLL_RUN_PREPARATION", "preparation-49", 7L,
                "payroll-prepare-49", JSON.createObjectNode()
                .put("preparationId", "preparation-49"), null, "/hr/operations/payroll");
    }

    static ProductSurfaceStepUpDtos.IssueRequest payrollCalculateRequest() {
        return new ProductSurfaceStepUpDtos.IssueRequest(
                "POST", "/api/payroll/v1/runs/run-49/attempts", "opaque-context",
                "PAY_GROUP_49", "PAYROLL_RUN", "run-49", 7L,
                "payroll-calculate-49", JSON.createObjectNode(), null,
                "/hr/operations/payroll");
    }

    static ProductSurfaceStepUpDtos.IssueRequest timeCloseRequest(String suffix) {
        String periodId = "00000000-0000-4000-8000-000000000050";
        return new ProductSurfaceStepUpDtos.IssueRequest(
                "POST", "/api/time/v1/close-periods/" + periodId + "/" + suffix,
                "opaque-context", "TIME_PERIOD_50", "TIME_PERIOD", periodId, 9L,
                "time-close-50-" + suffix, JSON.createObjectNode(), null,
                "/hr/operations/time");
    }

    static ProductSurfaceStepUpDtos.IssueRequest certificateIssueRequest() {
        String person = "00000000-0000-4000-8000-000000000052";
        return new ProductSurfaceStepUpDtos.IssueRequest(
                "POST", "/api/people/v1/hris/certificates", "opaque-context",
                "hrm009-selected-scope", "PERSON", person, 3L,
                "hrm009-certificate-issue-52",
                JSON.createObjectNode().put("personPublicId", person), null,
                "/hr/operations/people/certificates-documents");
    }

    static ProductSurfaceStepUpDtos.IssueRequest employmentContractIssueRequest() {
        String relationship = "00000000-0000-4000-8000-000000000053";
        return new ProductSurfaceStepUpDtos.IssueRequest(
                "POST", "/api/people/v1/hris/employment-contracts", "opaque-context",
                "hrm011-selected-scope", "WORK_RELATIONSHIP", relationship, 4L,
                "hrm011-employment-contract-issue-53",
                JSON.createObjectNode().put("relationshipPublicId", relationship), null,
                "/hr/operations/people/contracts-compensation-basis");
    }

    static ProductSurfaceStepUpDtos.IssueRequest countryPackActivationRequest() {
        String packId = "00000000-0000-4000-8000-000000000054";
        return new ProductSurfaceStepUpDtos.IssueRequest(
                "POST", "/api/payroll/v1/country-packs/" + packId + "/activate",
                "opaque-context", "pay003-selected-scope", "COUNTRY_PACK", packId, 7L,
                "pay003-country-pack-activate-54", JSON.createObjectNode(), null,
                "/hr/settings/statutory");
    }

    static ProductSurfaceStepUpDtos.IssueRequest tenRequest(String[] operation, String contextKey, String scope) {
        return new ProductSurfaceStepUpDtos.IssueRequest("POST", "/api/approvals" + operation[4], contextKey, scope,
                operation[2], TARGET.toString(), 7L, "source10-" + operation[0],
                JSON.createObjectNode().put("expectedVersion", 7), null, "/approvals");
    }

    static com.dwp.services.approval.security.ApprovalStepUpVerifier.CommandBinding tenOwnerBinding(
            ProductSurfaceStepUpDtos.IssueRequest request, ProductSurfaceAuthorityDtos.AuthorityResult authority,
            String route, String capability, String type, String target, long version, String hash, String path) {
        return new com.dwp.services.approval.security.ApprovalStepUpVerifier.CommandBinding(actor, tenant, route,
                request.contextKey(), "STEPUP-MGMT-HIGH-V1", capability, request.contextScopeKey(), type,
                target, version, "POST", path, request.idempotencyKey(), hash, revision(authority));
    }

    static ProductSurfaceAuthorityDtos.AuthorityResult currentAuthority(
            ProductSurfaceStepUpDtos.IssueRequest request, String routeKey) {
        var route = resolver.resolve(request);
        return authorityService.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant, actor, route.productKey(),
                route.surfaceKey(), ProductSurfaceAuthorityDtos.AccessMode.NORMAL, routeKey,
                null, null, null, null, List.of()));
    }

    static void createExplicitIsolatedScopeDuty() {
        tenant = jdbc.queryForObject("SELECT min(tenant_id) FROM com_tenants WHERE status='ACTIVE'", Long.class);
        actor = newUser("publisher"); long requester = newUser("maker"); checker = newUser("checker"); maker = requester;
        UUID set = jdbc.queryForObject("SELECT resource_set_id FROM com_admin_resource_sets WHERE tenant_id=? AND resource_set_key='RS_APPROVALS' AND lifecycle_state='ACTIVE'", UUID.class, tenant);
        responsibility = UUID.randomUUID(); resourceSet = set; var now = OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO com_admin_role_assignments(admin_role_assignment_id,tenant_id,principal_type,principal_ref,
                    responsibility_code,resource_set_id,assignment_source,lifecycle_state,valid_from,valid_to,review_due_at,
                    justification,approved_by,approved_at,decision_reason)
                VALUES(?,?,'USER',?,'APP_CONFIG_ADMIN',?,'MANUAL','ACTIVE',?,?,?, ?,?,CURRENT_TIMESTAMP,?)
                """, responsibility, tenant, Long.toString(actor), set, now.minusMinutes(1), now.plusHours(1),
                now.plusMinutes(30), "Explicit isolated approval configuration scope.", checker, "Independent scoped responsibility approval.");
        var assignments = context.getBean(ScopedAdminDutyAssignmentService.class);
        var pending = assignments.request(new ScopedAdminDutyAssignmentService.Request(tenant, "USER", Long.toString(actor),
                "APPROVAL_DESIGN_PUBLISH", set, responsibility, "MANUAL", now.minusMinutes(1), now.plusMinutes(20),
                now.plusMinutes(10), "Explicit isolated publish duty with no implicit grants.", requester));
        duty = assignments.approve(tenant, pending.assignmentId(), checker, pending.version(), "Independent scoped publish duty approval.").assignmentId();
        assertThat(context.getBean(ScopedAdminDutyEvidenceService.class).effectiveDuties(tenant, actor))
                .extracting(ScopedAdminDutyEvidenceService.EffectiveDuty::dutyCode).containsExactly("APPROVAL_DESIGN_PUBLISH");
    }

    static long newUser(String name) {
        return jdbc.queryForObject("INSERT INTO com_users(tenant_id,display_name,email,status) VALUES(?,?,?,'ACTIVE') RETURNING user_id",
                Long.class, tenant, "Isolated " + name, "stepup-" + name + "@isolated.test");
    }

    static ProductSurfaceStepUpDtos.IssueRequest request(String contextKey, String scope) {
        return new ProductSurfaceStepUpDtos.IssueRequest("POST", "/api/approvals/v1/admin/workflows/" + TARGET + "/publish",
                contextKey, scope, "WORKFLOW", TARGET.toString(), 7L, "latest-seven-original-key",
                JSON.createObjectNode().put("expectedVersion", 7), null, "/approvals");
    }

    static ProductSurfaceStepUpDtos.IssueRequest documentPolicyRequest(String contextKey, String scope) {
        return new ProductSurfaceStepUpDtos.IssueRequest("POST",
                "/api/approvals/v1/admin/document-tools/policies/" + TARGET + "/publish",
                contextKey, scope, "DOCUMENT_POLICY", TARGET.toString(), 7L, "document-policy-original-key",
                JSON.createObjectNode().put("expectedVersion", 7), null, "/approvals");
    }

    static void explicitGrant(String resource, String permission) {
        grantTo(actor, resource, permission);
    }

    static void grantTo(long user, String resource, String permission) {
        Long resourceId = jdbc.queryForObject("SELECT resource_id FROM com_resources WHERE tenant_id=? AND key=?",
                Long.class, tenant, resource);
        Long permissionId = jdbc.queryForObject("SELECT permission_id FROM com_permissions WHERE code=?",
                Long.class, permission);
        new com.dwp.services.auth.repository.PrincipalResourceGrantRepository(jdbc).grant(tenant, "USER",
                Long.toString(user), resourceId, permissionId, "ADMIN_DIRECT",
                "isolated-" + resource + (user == actor ? "" : "-" + user),
                OffsetDateTime.now().plusMinutes(10), "Explicit isolated current-authority runtime grant.", checker);
    }

    static ProductSurfaceAuthorityDtos.EvaluateRequest adminCandidates(long user, String contextKey, String scope) {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant, user, "approvals", "approvals.admin",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL, "route.approvals.admin.form-field-candidates.data",
                contextKey, scope, null, null, List.of());
    }

    static String adminSearchBody(ApprovalFormUserSourceProofVerifier proofs,
            ProductSurfaceAuthorityDtos.AuthorityResult current,
            java.util.function.Consumer<java.util.Map<String, Object>> mutation) {
        try {
            var body = java.util.Map.of("query", "Kim", "size", 10);
            var claims = ApprovalFormUserProofTestSupport.claims("SEARCH", proofs.requestDigest("SEARCH", body));
            var now = Instant.now();
            claims.put("iat", now.getEpochSecond()); claims.put("nbf", now.getEpochSecond());
            claims.put("exp", now.plusSeconds(30).getEpochSecond()); claims.put("jti", UUID.randomUUID().toString());
            claims.put("tenantId", tenant); claims.put("actorId", actor);
            claims.put("routeContractKey", "route.approvals.admin.form-field-candidates.data");
            claims.put("referencePurpose", "FORM_PREVIEW"); claims.put("contextKey", current.contextKey());
            claims.put("contextScopeKey", current.scopes().getFirst().key()); claims.put("decisionRevision", revision(current));
            mutation.accept(claims);
            return JSON.writeValueAsString(java.util.Map.of("sourceProof", ApprovalFormUserProofTestSupport.sign(claims),
                    "query", "Kim", "size", 10));
        } catch (Exception error) { throw new AssertionError(error); }
    }

    static void assertAdminLookupDenied(ApprovalFormUserDirectoryService service, String body) {
        assertThatThrownBy(() -> service.search(body)).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    static JsonNode verifySignature(ProductSurfaceStepUpChallengeService.Issued issued) {
        try {
            String[] token = issued.response().challenge().split("\\.");
            var signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(keys.getPublic());
            signature.update((token[0] + '.' + token[1]).getBytes(StandardCharsets.US_ASCII));
            assertThat(signature.verify(Base64.getUrlDecoder().decode(token[2]))).isTrue();
            return JSON.readTree(Base64.getUrlDecoder().decode(token[1]));
        } catch (Exception error) { throw new AssertionError(error); }
    }

    static String payloadDigest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("{\"expectedVersion\":7}".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new AssertionError(error); }
    }

    static ProductSurfaceStepUpDtos.IssueRequest changed(ProductSurfaceStepUpDtos.IssueRequest original,
            String method, String path, String type, String target, JsonNode payload) {
        return new ProductSurfaceStepUpDtos.IssueRequest(method, path, original.contextKey(), original.contextScopeKey(),
                type, target, original.expectedObjectVersion(), original.idempotencyKey(), payload, null, original.returnTo());
    }

    static ProductSurfaceStepUpChallengeService.Outcome issue(ProductSurfaceStepUpDtos.IssueRequest request, String revision) {
        return issue(challenges, request, revision);
    }

    static ProductSurfaceStepUpChallengeService.Outcome issue(ProductSurfaceStepUpChallengeService signer,
            ProductSurfaceStepUpDtos.IssueRequest request, String revision) {
        var jwt = Jwt.withTokenValue("verified-session").header("alg", "RS256").subject(Long.toString(actor))
                .claim("jti", "verified-jti").claim("sid", UUID.randomUUID().toString()).build();
        var parsed = context.getBean(ProductSurfaceStepUpRequestParser.class).parse(JSON.valueToTree(request).toString());
        return signer.issue(actor, tenant, jwt, parsed, revision, new MockHttpServletResponse());
    }

    static String revision(ProductSurfaceAuthorityDtos.AuthorityResult authority) {
        try {
            return "psr-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",
                    Long.toString(tenant), Long.toString(actor), "NORMAL", authority.authRevision(), authority.policyRevision(),
                    "", "", "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    static void assertNoChallenge(ProductSurfaceStepUpDtos.IssueRequest request, String revision) {
        assertThatThrownBy(() -> issue(request, revision)).isInstanceOf(BaseException.class);
    }

    static void rollback(Runnable work) {
        transactions.executeWithoutResult(status -> { try { work.run(); } finally { status.setRollbackOnly(); } });
    }
}
