package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;
import static org.mockito.Mockito.when;

class ApprovalDocumentPostgresFixture extends ApprovalDraftPostgresFixture {
    static final Set<String> DOC_PERMISSIONS = Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:CREATE",
            "ACTION.APPROVAL_REQUEST:UPDATE", "ACTION.APPROVAL_REQUEST:VIEW", "ACTION.APPROVAL_REQUEST:EXPORT",
            "ACTION.APPROVAL_TASK:VIEW", "ACTION.APPROVAL_TASK:UPDATE", "ACTION.APPROVAL_TASK:APPROVE", "ACTION.APPROVAL_TASK:EXPORT",
            "ADMIN.APPROVAL_POLICY:VIEW", "ADMIN.APPROVAL_POLICY:UPDATE", "ADMIN.APPROVAL_POLICY:PUBLISH");
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ApprovalDocumentCanonical canonical;
    ApprovalDocumentRepository documentsRepository;
    ApprovalDocumentService documents;
    ApprovalDocumentManagementService management;
    ApprovalStepUpVerifier verifier;
    KeyPair keys;

    void initializeDocuments(PostgreSQLContainer<?> postgres) throws Exception {
        initialize(postgres); canonical = new ApprovalDocumentCanonical(mapper);
        var named = new NamedParameterJdbcTemplate(jdbc);
        documentsRepository = new ApprovalDocumentRepository(named, canonical);
        var owners = new ApprovalDocumentOwnerRepository(named);
        var authority = new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities), identities, owners, canonical);
        var policy = new ApprovalDocumentPolicy();
        var audit = new ApprovalDocumentAudit(new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test"));
        documents = new ApprovalDocumentService(authority, owners, documentsRepository, policy, new ApprovalDocumentRenderer(canonical), audit);
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
        verifier = new ApprovalStepUpVerifier(mapper, Clock.systemUTC(), keys.getPublic(), "auth-test", "dwp-approval-server", "key1", "urn:dwp:acr:mfa", 600, 900);
        var publish = new ApprovalDocumentPublishGuard(verifier, new ApprovalStepUpReplayRepository(named, mapper));
        management = new ApprovalDocumentManagementService(authority, documentsRepository, owners, policy, publish, audit);
        when(identities.require(42, 99)).thenReturn(documentSubject(99, List.of("APPROVAL_OPERATOR"), DOC_PERMISSIONS));
        when(identities.require(42, 100)).thenReturn(documentSubject(100, List.of("APPROVAL_OPERATOR"), DOC_PERMISSIONS));
        docContext(99);
    }
    static ApprovalIdentityDirectory.Subject documentSubject(long id, List<String> roles, Set<String> permissions) {
        return new ApprovalIdentityDirectory.Subject(42L, id, null, null, "Owner", "owner@example.test", null, "ACTIVE", roles, List.copyOf(permissions));
    }
    static void docContext(long actor) {
        clear(); ApprovalRequestContext.set(actor, 42L, null, "Owner", Set.of("APPROVAL_OPERATOR"), DOC_PERMISSIONS);
    }
    void exactPublish(long actor, String route, String state) {
        docContext(actor);
        ApprovalManagementScopeContext.set("scope-doc", "RS_APPROVALS");
        ApprovalDecisionRevisionContext.set("psr-" + "a".repeat(64), OffsetDateTime.now().plusMinutes(5), "context-doc", "scope-doc", route, state);
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route, "ACTION", "APP_CONFIG_ADMIN", false,
                Set.of("predicate.approval.document-policy.v1"), "approvals.policy.publish", "STEPUP-MGMT-HIGH-V1", "SOD-DOC", true, null, null)));
    }
    ApprovalStepUpHeaders signed(String route, String type, UUID target, long version, String path, String key, Object payload) throws Exception {
        var actor = ApprovalRequestContext.require(); var evidence = ApprovalDecisionRevisionContext.current().orElseThrow();
        var binding = new ApprovalStepUpVerifier.CommandBinding(actor.userId(), actor.tenantId(), route, evidence.contextKey(),
                "STEPUP-MGMT-HIGH-V1", "approvals.policy.publish", evidence.contextScopeKey(), type, target.toString(), version,
                "POST", path, key, verifier.payloadSha256(payload), evidence.revision());
        Instant now = Instant.now(); var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", "auth-test"); claims.put("aud", "dwp-approval-server"); claims.put("sub", actor.userId().toString());
        claims.put("iat", now.getEpochSecond()); claims.put("nbf", now.getEpochSecond()); claims.put("exp", now.plusSeconds(300).getEpochSecond());
        claims.put("auth_time", now.getEpochSecond()); claims.put("acr", "urn:dwp:acr:mfa"); claims.put("amr", List.of("mfa"));
        claims.put("jti", UUID.randomUUID().toString()); claims.put("nonce", UUID.randomUUID().toString()); claims.put("tenant_id", actor.tenantId());
        claims.put("owner_service_key", "approval"); claims.put("command_contract_key", route); claims.put("activation_policy", binding.activationPolicy());
        claims.put("capability_contract_key", binding.capabilityContractKey()); claims.put("context_key", binding.contextKey()); claims.put("scope_ref", binding.scopeRef());
        claims.put("target_type", type); claims.put("target_id", target.toString()); claims.put("target_version", version); claims.put("command_method", "POST");
        claims.put("command_path", path); claims.put("idempotency_key", key); claims.put("payload_sha256", binding.payloadSha256());
        claims.put("decision_revision", binding.decisionRevision());
        claims.put("command_sha256", ProductSurfaceStepUpChallengeContract.commandSha256(new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                route, "approval", "dwp-approval-server", "POST", path, binding.contextKey(), binding.scopeRef(), type, target.toString(), version, key, binding.payloadSha256(), binding.decisionRevision())));
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String material = encoder.encodeToString(mapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", "key1"))) + "."
                + encoder.encodeToString(mapper.writeValueAsBytes(claims));
        var signature = Signature.getInstance("SHA256withRSA"); signature.initSign(keys.getPrivate()); signature.update(material.getBytes(StandardCharsets.US_ASCII));
        return new ApprovalStepUpHeaders(material + "." + encoder.encodeToString(signature.sign()), key, evidence.revision(), version);
    }
    Rules exportingRules() {
        return new Rules(true, true, true, true, true, false, List.of("RESTRICTED"),
                List.of(new FieldRule("systemName", FieldType.STRING, 200, List.of())), 20, 1048576, 300, 365);
    }
    Policy publishRules(Rules rules) throws Exception {
        docContext(99); var policy = tx(management::policy);
        var pending = tx(() -> management.save(policy.policyId(), new SavePolicy(policy.version(), "policy-draft", rules)));
        String route = ApprovalDocumentManagementService.POLICY_PUBLISH;
        exactPublish(100, route, "110"); var input = new PublishPolicy(pending.version(), "policy-publish", "Independent policy review");
        var headers = signed(route, "DOCUMENT_POLICY", pending.policyId(), pending.version(), "/v1/admin/document-tools/policies/" + pending.policyId() + "/publish", input.idempotencyKey(), input);
        var result = tx(() -> management.publish(pending.policyId(), input, headers)); docContext(99); return result;
    }
}
