package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalPilotPepRegistry;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.auth.approvalsignatures.SignatureAuthorityBindings;
import com.dwp.services.auth.approvalsignatures.SignatureAuthorityProtocol;
import com.dwp.services.auth.service.WorkflowRuntimeActualAuthHarness;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.mock.web.MockHttpServletRequest;

/** Proves the real Approval installed-source seal binds to Auth's active v32 registry. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovalSignatureInstalledSourceAuthInteropPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String RESOURCE_SET = "RS_APPROVALS";
    private static final Set<String> PERMISSIONS = Set.of(
            "APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:VIEW");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ApprovalSignatureCanonical canonical = new ApprovalSignatureCanonical(mapper);
    private ApprovalPilotPepRegistry registry;
    private WorkflowRuntimeActualAuthHarness auth;

    @BeforeAll
    void start() throws Exception {
        registry = new ApprovalPilotPepRegistry(mapper);
        auth = new WorkflowRuntimeActualAuthHarness(
                new RSAKeyGenerator(2048).keyID("approval-source-v32-owner").generate(),
                new RSAKeyGenerator(2048).keyID("approval-source-v32-transport").generate(),
                new RSAKeyGenerator(2048).keyID("approval-source-v32-attestation").generate());
        auth.activateProductAuthorization(32);
    }

    @AfterEach
    void clearRequestEvidence() throws Exception {
        ApprovalRequestContext.clear();
        invokeContext(ApprovalPilotAuthorizationContext.class, "clear", new Class<?>[0]);
        invokeContext(ApprovalDecisionRevisionContext.class, "clear", new Class<?>[0]);
    }

    @AfterAll
    void stop() {
        if (auth != null) auth.close();
    }

    @Test
    void installedSignatureSourceSealIsAcceptedByTheActiveAuthV32Bridge() throws Exception {
        UUID objectId = UUID.randomUUID();
        JsonNode body = mapper.createObjectNode().put("operation", "read-current-signature");
        var binding = new ApprovalSignatureAuthority.Binding(
                ApprovalSignatureAuthority.Operation.GET, objectId, null,
                canonical.digest(body), null, body);
        install(binding.operation().route(), binding.operation().method(),
                binding.operation().path(objectId), "approvals.work.signature.read");
        MockHttpServletRequest request = request(binding.operation().method(),
                binding.operation().path(objectId));

        var seal = new ApprovalSignatureInstalledSource(canonical, clock, registry)
                .capture(request, binding);
        var authBinding = authBinding(SignatureAuthorityProtocol.Operation.GET,
                objectId, null, binding.bodySha256(), body, seal);

        assertThat(seal.registrySha256()).isEqualTo(registry.registryRef().sha256());
        assertThat(registry.registryRef().version()).isEqualTo(32);
        assertThatCode(() -> auth.requireApprovalSignatureRegistry(authBinding))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> auth.requireApprovalSignatureRegistry(
                withRegistry(authBinding,
                        "65155dcc88f454a0ad2530518f8ec9b0c070afd31d583a19f980dd3d10f78a74")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void installedCommandReceiptSealIsAcceptedByTheActiveAuthV32Bridge() throws Exception {
        UUID targetId = UUID.randomUUID();
        var query = new ApprovalSignatureCommandReceiptDtos.Query(
                "receipt-v32-source", ApprovalSignatureCommandReceiptDtos.OriginalOperation.CREATE,
                targetId, "d".repeat(64));
        install(ApprovalSignatureCommandReceiptDtos.ROUTE, "GET", query.path(),
                "approvals.work.signature.read");
        MockHttpServletRequest request = request("GET", query.path());
        request.addParameter("originalOperation", query.originalOperation().name());
        request.addParameter("targetId", query.targetId().toString());
        request.addParameter("bodySha256", query.bodySha256());
        request.setQueryString("originalOperation=" + query.originalOperation().name()
                + "&targetId=" + query.targetId() + "&bodySha256=" + query.bodySha256());

        var seal = new ApprovalSignatureReceiptInstalledSource(canonical, clock, registry)
                .capture(request, query);
        JsonNode body = mapper.valueToTree(query.body());
        var authBinding = authBinding(SignatureAuthorityProtocol.Operation.COMMAND_RECEIPT,
                targetId, query.idempotencyKey(), canonical.digest(body), body, seal);

        assertThat(seal.registrySha256()).isEqualTo(registry.registryRef().sha256());
        assertThatCode(() -> auth.requireApprovalSignatureRegistry(authBinding))
                .doesNotThrowAnyException();
    }

    private void install(String route, String method, String path, String capability)
            throws Exception {
        String permission = "ACTION.APPROVAL_REQUEST:VIEW";
        var decision = registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence(
                method, path, PERMISSIONS,
                ScopedAuthorityToken.wireToken(capability, permission, RESOURCE_SET),
                Set.of("WORKSPACE_USER"), route,
                ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authorities()).hasSize(1);
        invokeContext(ApprovalPilotAuthorizationContext.class, "set",
                new Class<?>[]{List.class}, decision.authorities());
        invokeContext(ApprovalDecisionRevisionContext.class, "set",
                new Class<?>[]{String.class, OffsetDateTime.class, String.class,
                        String.class, String.class, String.class},
                "psr-" + "a".repeat(64), NOW.plusSeconds(60).atOffset(ZoneOffset.UTC),
                "approval-source-v32", "approval-source-v32-scope", route, "111");
        ApprovalRequestContext.set(101L, auth.tenantId(), UUID.randomUUID(),
                Set.of("WORKSPACE_USER"), PERMISSIONS);
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(path);
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        return request;
    }

    private SignatureAuthorityBindings authBinding(
            SignatureAuthorityProtocol.Operation operation, UUID objectId,
            String idempotencyKey, String bodySha256, JsonNode body,
            ApprovalSignatureInstalledSource.Seal seal) {
        return new SignatureAuthorityBindings(
                operation, seal.actor().tenantId(), seal.actor().userId(),
                seal.actor().personPublicId(), objectId, null, idempotencyKey,
                bodySha256, seal.evidence().contextKey(),
                seal.evidence().contextScopeKey(), RESOURCE_SET,
                seal.evidence().revision(), seal.registrySha256(),
                seal.evidence().rolloutState(), seal.mode(),
                seal.evidence().validUntil().toInstant(), UUID.randomUUID(),
                mapper.createObjectNode(), body, "e".repeat(64), null);
    }

    private static SignatureAuthorityBindings withRegistry(
            SignatureAuthorityBindings source, String registrySha256) {
        return new SignatureAuthorityBindings(
                source.operation(), source.tenantId(), source.actorId(),
                source.personPublicId(), source.objectId(), source.objectVersion(),
                source.idempotencyKey(), source.bodySha256(), source.contextKey(),
                source.contextScopeKey(), source.resourceSetKey(),
                source.decisionRevision(), registrySha256, source.rolloutState(),
                source.accessMode(), source.authorityValidUntil(), source.nonce(),
                source.source(), source.commandBody(), source.sourceSha256(),
                source.stepUpToken());
    }

    private static Object invokeContext(
            Class<?> type, String name, Class<?>[] parameters, Object... arguments)
            throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }
}
