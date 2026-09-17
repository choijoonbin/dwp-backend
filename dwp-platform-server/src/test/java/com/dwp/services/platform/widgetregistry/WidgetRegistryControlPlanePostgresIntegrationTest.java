package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.HomeExperienceService;
import com.dwp.services.platform.provisioning.PlatformTenantProvisioningDtos;
import com.dwp.services.platform.provisioning.PlatformTenantProvisioningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({
        WidgetRegistryControlPlanePostgresIntegrationTest.JacksonConfiguration.class,
        WidgetRegistryResponseMapper.class,
        WidgetRegistryCommandReceiptService.class,
        WidgetRegistryLedger.class,
        WidgetRegistryImpactService.class,
        WidgetRegistryMutationGuard.class,
        WidgetRegistryOwnerScopeGuard.class,
        WidgetRegistryDefinitionService.class,
        WidgetRegistryReleaseService.class,
        TenantWidgetPolicyService.class,
        WidgetRuntimeControlService.class,
        WidgetRegistryAuditService.class,
        WidgetCatalogService.class
})
class WidgetRegistryControlPlanePostgresIntegrationTest {
    private static final Path FIXTURE =
            Path.of("../contracts/widget-registry/native-widget-manifests.v1.json");
    private static final String EXPECTED_BINDING_CATALOG_REVISION =
            "2fcb00cb8df02d953d5e4be94d7659dd937f951a02fba3aeabefa73de30a024b";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.locations",
                () -> "filesystem:src/main/resources/db/migration");
    }

    @Autowired private WidgetRegistryDefinitionService definitions;
    @Autowired private WidgetRegistryReleaseService releases;
    @Autowired private TenantWidgetPolicyService policies;
    @Autowired private WidgetRuntimeControlService controls;
    @Autowired private WidgetRegistryAuditService audit;
    @Autowired private WidgetCatalogService catalog;
    @Autowired private WidgetRegistryLedger ledger;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void providerServiceReadsAreFilteredAndCrossOwnerDetailFailsClosed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-DWP-Identity-Plane", "PROVIDER");
        request.addHeader("X-DWP-Control-Plane", "WIDGET_REGISTRY_PROVIDER");
        request.addHeader(WidgetRegistryOwnerScopeGuard.OWNER_SCOPE_HEADER, "core.work");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        var page = definitions.list(0, 100, null);
        assertThat(page.items()).isNotEmpty()
                .allSatisfy(item -> assertThat(item.ownerProductKey()).isEqualTo("core.work"));
        UUID anotherOwner = jdbc.queryForObject("""
                SELECT definition_id FROM plt_widget_definitions
                 WHERE owner_product_key <> 'core.work'
                 ORDER BY definition_key LIMIT 1
                """, UUID.class);

        assertThatThrownBy(() -> definitions.get(anotherOwner))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void readinessReflectsPersistedNativeBaselineInsteadOfAConstant() {
        var ready = catalog.readiness();
        assertThat(ready.controlPlaneReady()).isTrue();
        assertThat(ready.runtimeActivationReady()).isFalse();
        assertThat(ready.migrationMode()).isEqualTo("SHADOW");

        jdbc.update("""
                UPDATE plt_widget_renderer_bindings SET binding_state = 'DISABLED'
                 WHERE renderer_key = 'home.command-rail'
                """);
        assertThat(catalog.readiness().controlPlaneReady()).isFalse();

        jdbc.update("""
                UPDATE plt_widget_renderer_bindings SET binding_state = 'ACTIVE'
                 WHERE renderer_key = 'home.command-rail'
                """);
        assertThat(catalog.readiness().controlPlaneReady()).isTrue();
    }

    @Test
    void concurrentDuplicateCommandProducesOneDefinitionEventAndReceipt() throws Exception {
        UUID commandId = UUID.randomUUID();
        String key = "core.work.concurrent-" + commandId.toString().substring(0, 8);
        var request = new WidgetRegistryDtos.DefinitionCreateRequest(
                key, null, "core.work", "dwp-home", "LOW", "INTERNAL",
                "TEST", "concurrent idempotency", 0L);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return definitions.create(42001L, commandId, "concurrent", request);
            });
            var second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return definitions.create(42001L, commandId, "concurrent", request);
            });
            start.countDown();

            var firstResponse = first.get(30, TimeUnit.SECONDS);
            var secondResponse = second.get(30, TimeUnit.SECONDS);
            assertThat(secondResponse).isEqualTo(firstResponse);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM plt_widget_command_receipts
                 WHERE actor_id = 42001 AND command_id = ?
                """, Integer.class, commandId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM plt_widget_registry_events
                 WHERE event_type = 'WIDGET_DEFINITION_CREATED'
                   AND aggregate_id = ?
                """, Integer.class,
                jdbc.queryForObject("""
                        SELECT definition_id::text FROM plt_widget_definitions
                         WHERE definition_key = ?
                        """, String.class, key))).isEqualTo(1);
    }

    @Test
    void migratedAndNewTenantPoliciesMatchAndRemainShadowDiscoverable() throws Exception {
        Long migratedTenantId = jdbc.queryForObject("""
                SELECT min(tenant_id) FROM sys_service_tenants
                 WHERE lifecycle_state <> 'RETIRED'
                """, Long.class);
        assertThat(migratedTenantId).isNotNull();
        long newTenantId = 92571L;
        var provisioning = new PlatformTenantProvisioningService(
                jdbc, Path.of(System.getProperty("java.io.tmpdir"), "dwp-wave3-catalog-test").toString(),
                objectMapper);
        provisioning.provision(new PlatformTenantProvisioningDtos.ProvisionTenantRequest(
                UUID.fromString("92571000-0000-0000-0000-000000000001"), newTenantId,
                "wave-three-catalog", "Wave Three Catalog Tenant", "local", "POOL", "ko",
                List.of("core.workspace")));

        assertThat(policyProjection(newTenantId)).isEqualTo(policyProjection(migratedTenantId));
        for (long tenantId : new long[]{migratedTenantId, newTenantId}) {
            assertThat(jdbc.queryForObject("""
                    SELECT policy.revision_number || '|' || policy.enabled::text || '|'
                           || policy.reason_code
                      FROM adm_tenant_widget_policy_heads head
                      JOIN adm_tenant_widget_policy_revisions policy
                        ON policy.policy_revision_id = head.current_revision_id
                      JOIN plt_widget_definitions definition
                        ON definition.definition_id = head.definition_id
                     WHERE head.tenant_id = ?
                       AND definition.definition_key = 'dwaion.artifact'
                    """, String.class, tenantId)).isEqualTo(
                    "2|true|DWAION_HOME_SIGNED_OWNER_PROVIDER");
        }
        String authorities = requiredAuthorities();
        assertShadowCatalogAvailable(migratedTenantId, authorities);
        assertShadowCatalogAvailable(newTenantId, authorities);
    }

    @Test
    void failedCertificationEvidenceDisablesLegacyShadowDiscovery() throws Exception {
        UUID seedId = UUID.fromString("31000000-0000-0000-0000-000000000003");
        var seed = definitions.getVersion(seedId);
        assertShadowCatalogAvailable(1L, requiredAuthorities());

        definitions.recordEvidence(41002L, UUID.randomUUID(), "legacy-failure", seedId,
                new WidgetRegistryDtos.EvidenceCreateRequest(
                        "SECURITY", "FAIL", seed.manifestHash(), "evidence:security-failure",
                        sha("security-failure"), null, null,
                        "TEST", "Security evidence failed", seed.version()));

        assertThat(definitions.getVersion(seedId).certificationStatus()).isEqualTo("FAIL");
        assertCatalogDenied(seedId);
    }

    @Test
    void expiredLegacyCertificationFailsClosed() throws Exception {
        UUID seedId = UUID.fromString("31000000-0000-0000-0000-000000000003");
        jdbc.update("UPDATE plt_widget_definition_versions SET certification_status = 'EXPIRED' WHERE version_id = ?",
                seedId);
        assertCatalogDenied(seedId);
    }

    @Test
    void copiedAndAlteredLegacyAttestationsCannotCreateAnEighthShadowException() throws Exception {
        UUID original = UUID.fromString("31000000-0000-0000-0000-000000000003");
        UUID copy = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plt_widget_definition_versions (
                    version_id, definition_id, semantic_version, manifest, manifest_hash,
                    renderer_key, workflow_state, release_state, safety_state, immutable,
                    attestation, certification_status)
                SELECT ?, definition_id, '2.0.0', manifest, ?, renderer_key, workflow_state,
                       release_state, safety_state, immutable, attestation, certification_status
                  FROM plt_widget_definition_versions WHERE version_id = ?
                """, copy, sha("altered-manifest"), original);
        jdbc.update("""
                UPDATE plt_widget_release_channels SET current_version_id = ?
                 WHERE definition_id = '30000000-0000-0000-0000-000000000003'
                   AND channel = 'STABLE'
                """, copy);

        assertCatalogDenied(copy);
    }

    @Test
    void correctedLegacyShadowExceptionRequiresCanonicalOwnershipAndSupersedesOldVersion() throws Exception {
        UUID corrected = UUID.fromString("31000000-0000-0000-0000-000000000106");
        assertShadowCatalogAvailable(1L, requiredAuthorities());

        jdbc.update("""
                UPDATE plt_widget_renderer_bindings
                   SET source_app_resource_key = 'APP.WORK'
                 WHERE renderer_key = 'home.focus-balance'
                """);
        entityManager.clear();
        assertCatalogDenied(corrected);

        jdbc.update("""
                UPDATE plt_widget_renderer_bindings
                   SET source_app_resource_key = 'APP.CALENDAR'
                 WHERE renderer_key = 'home.focus-balance'
                """);
        jdbc.update("""
                UPDATE plt_widget_definitions
                   SET owner_product_key = 'core.work'
                 WHERE definition_id = '30000000-0000-0000-0000-000000000006'
                """);
        entityManager.clear();
        assertCatalogDenied(corrected);

        jdbc.update("""
                UPDATE plt_widget_release_channels
                   SET current_version_id = '31000000-0000-0000-0000-000000000006',
                       previous_version_id = '31000000-0000-0000-0000-000000000106'
                 WHERE definition_id = '30000000-0000-0000-0000-000000000006'
                   AND channel = 'STABLE'
                """);
        entityManager.clear();
        assertCatalogDenied(UUID.fromString("31000000-0000-0000-0000-000000000006"));
    }

    private void assertCatalogDenied(UUID versionId) throws Exception {
        var response = catalog.effective(1L, "workspace-home", requiredAuthorities(), "", "");
        assertThat(response.contexts().getFirst().items())
                .filteredOn(item -> versionId.equals(item.resolvedVersionId()))
                .singleElement().satisfies(item -> {
                    assertThat(item.effectiveState()).isEqualTo(WidgetRegistryDtos.EffectiveCatalogState.DENY);
                    assertThat(item.reasonCodes()).doesNotContain(WidgetRegistryDtos.EffectiveCatalogReason.AVAILABLE);
                    assertThat(item.placementCapabilities().canAdd()).isFalse();
                });
    }

    @Test
    void lifecyclePolicySafetyRollbackConcurrencyAndAuditRemainFailClosed() throws Exception {
        long author = 41001L;
        long reviewer = 41002L;
        long releaser = 41003L;
        JsonNode manifest = focusManifest("core.work.wave3-contract-test");

        var definition = definitions.create(author, UUID.randomUUID(), "wave3-definition",
                new WidgetRegistryDtos.DefinitionCreateRequest(
                        "core.work.wave3-contract-test", null, "core.work", "dwp-home",
                        "HIGH", "CONFIDENTIAL", "TEST", "integration lifecycle", 0L));
        var draft = definitions.createVersion(author, UUID.randomUUID(), "wave3-version",
                definition.definitionId(), new WidgetRegistryDtos.VersionCreateRequest(
                        "1.0.0+integration.1", manifest, null,
                        "TEST", "create version", definition.version()));

        assertThatThrownBy(() -> definitions.validate(author, UUID.randomUUID(), null,
                draft.versionId(), new WidgetRegistryDtos.ValidateRequest(
                        "0".repeat(64), "TEST", "wrong hash", draft.version())))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        var validation = definitions.validate(author, UUID.randomUUID(), null, draft.versionId(),
                new WidgetRegistryDtos.ValidateRequest(
                        draft.manifestHash(), "TEST", "validate", draft.version()));
        List<UUID> evidenceIds = new ArrayList<>();
        for (String type : List.of(
                "MANIFEST", "SECURITY", "PRIVACY", "A11Y", "PERFORMANCE", "LOCALIZATION")) {
            var current = definitions.getVersion(draft.versionId());
            var evidence = definitions.recordEvidence(reviewer, UUID.randomUUID(), null,
                    draft.versionId(), new WidgetRegistryDtos.EvidenceCreateRequest(
                            type, "PASS", draft.manifestHash(), "evidence:" + type.toLowerCase(),
                            sha(type), OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), null,
                            "TEST", "certification evidence", current.version()));
            evidenceIds.add(evidence.evidenceId());
        }

        var current = definitions.getVersion(draft.versionId());
        var submitted = definitions.submit(author, UUID.randomUUID(), null, draft.versionId(),
                new WidgetRegistryDtos.WidgetVersionTransitionRequest(
                        "TEST", "submit", current.version()));
        assertThatThrownBy(() -> definitions.decide(author, UUID.randomUUID(), null,
                draft.versionId(), new WidgetRegistryDtos.ReviewDecisionRequest(
                        "APPROVE", validation.validationRunId(), evidenceIds,
                        "TEST", "self approval", submitted.version())))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
        var approved = definitions.decide(reviewer, UUID.randomUUID(), null, draft.versionId(),
                new WidgetRegistryDtos.ReviewDecisionRequest(
                        "APPROVE", validation.validationRunId(), evidenceIds,
                        "TEST", "approve", submitted.version()));
        assertThat(approved.certificationStatus()).isEqualTo("PASS");

        var publishImpact = releases.impact(draft.versionId(), "PUBLISH");
        var publishRequest = new WidgetRegistryDtos.PublishRequest(
                "STABLE", validation.validationRunId(), draft.manifestHash(), evidenceIds,
                publishImpact.impactRevision(), "TEST", "publish", approved.version());
        jdbc.update("""
                UPDATE plt_widget_renderer_bindings SET binding_state = 'DISABLED'
                 WHERE renderer_key = 'home.focus'
                """);
        assertThatThrownBy(() -> releases.publish(
                releaser, UUID.randomUUID(), null, draft.versionId(), publishRequest))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        jdbc.update("""
                UPDATE plt_widget_renderer_bindings SET binding_state = 'ACTIVE'
                 WHERE renderer_key = 'home.focus'
                """);
        assertThatThrownBy(() -> releases.publish(
                reviewer, UUID.randomUUID(), null, draft.versionId(), publishRequest))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
        var published = releases.publish(releaser, UUID.randomUUID(), null, draft.versionId(),
                publishRequest);
        assertThat(published.releaseState()).isEqualTo("PUBLISHED");
        assertThat(published.allowedTransitions())
                .contains("BLOCK", "DEPRECATE", "QUARANTINE", "REVOKE");

        CertifiedVersion next = certifyVersion(
                definition.definitionId(), definition.definitionKey(), "1.1.0+integration.1",
                draft.versionId(), author, reviewer);
        var nextPublishImpact = releases.impact(next.version().versionId(), "PUBLISH");
        var nextPublished = releases.publish(releaser, UUID.randomUUID(), null,
                next.version().versionId(), new WidgetRegistryDtos.PublishRequest(
                        "STABLE", next.validationRunId(), next.version().manifestHash(), next.evidenceIds(),
                        nextPublishImpact.impactRevision(), "TEST", "publish replacement",
                        next.version().version()));
        assertThat(releases.channel(definition.definitionId(), "STABLE").currentVersionId())
                .isEqualTo(nextPublished.versionId());

        var rollbackChannelImpact = releases.channelImpact(
                definition.definitionId(), "STABLE", "ROLLBACK", published.versionId());
        var channelBeforeRollback = releases.channel(definition.definitionId(), "STABLE");
        var rolledBackChannel = releases.rollback(releaser, UUID.randomUUID(), null,
                definition.definitionId(), "STABLE", new WidgetRegistryDtos.ChannelRollbackRequest(
                        published.versionId(), nextPublished.versionId(),
                        rollbackChannelImpact.impactRevision(), "TEST", "rollback release channel",
                        channelBeforeRollback.version()));
        assertThat(rolledBackChannel.currentVersionId()).isEqualTo(published.versionId());
        assertThat(rolledBackChannel.previousVersionId()).isEqualTo(nextPublished.versionId());

        assertThatThrownBy(() -> releases.deprecate(releaser, UUID.randomUUID(), null,
                nextPublished.versionId(), new WidgetRegistryDtos.DeprecateRequest(
                        nextPublished.versionId(), OffsetDateTime.now(ZoneOffset.UTC).plusDays(30),
                        "TEST", "self replacement", nextPublished.version())))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        for (OffsetDateTime invalidDeadline : List.of(
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(366))) {
            assertThatThrownBy(() -> releases.deprecate(releaser, UUID.randomUUID(), null,
                    nextPublished.versionId(), new WidgetRegistryDtos.DeprecateRequest(
                            published.versionId(), invalidDeadline,
                            "TEST", "invalid deadline", nextPublished.version())))
                    .isInstanceOfSatisfying(BaseException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        }
        UUID deprecationCommand = UUID.randomUUID();
        OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).withNano(0);
        var deprecationRequest = new WidgetRegistryDtos.DeprecateRequest(
                published.versionId(), deadline,
                "TEST", "deprecate replacement", nextPublished.version());
        var deprecated = releases.deprecate(releaser, deprecationCommand, null,
                nextPublished.versionId(), deprecationRequest);
        assertThat(deprecated.releaseState()).isEqualTo("DEPRECATED");
        assertThat(deprecated.replacementVersionId()).isEqualTo(published.versionId());
        assertThat(deprecated.deprecationEndsAt()).isEqualTo(deadline);
        assertThat(definitions.getVersion(deprecated.versionId()).deprecationEndsAt()).isEqualTo(deadline);
        assertThat(releases.deprecate(releaser, deprecationCommand, null,
                nextPublished.versionId(), deprecationRequest)).isEqualTo(deprecated);
        assertThat(jdbc.queryForObject("""
                SELECT deprecation_ends_at FROM plt_widget_definition_versions WHERE version_id = ?
                """, OffsetDateTime.class, deprecated.versionId())).isEqualTo(deadline);
        List<String> retainedDeadlines = jdbc.queryForList("""
                SELECT response_payload -> 'deprecationEndsAt' FROM plt_widget_command_receipts
                 WHERE command_id = ?
                UNION ALL
                SELECT request_audit_payload -> 'deprecationEndsAt' FROM plt_widget_command_receipts
                 WHERE command_id = ?
                UNION ALL
                SELECT after_snapshot -> 'deprecationEndsAt' FROM plt_widget_registry_events
                 WHERE command_id = ?
                """, String.class, deprecationCommand, deprecationCommand, deprecationCommand);
        assertThat(retainedDeadlines).hasSize(3);
        for (String payload : retainedDeadlines) {
            assertThat(objectMapper.readValue(payload, OffsetDateTime.class)).isEqualTo(deadline);
        }

        var absent = policies.get(1L, definition.definitionId());
        var policyDraft = policies.createRevision(1L, author, UUID.randomUUID(), null,
                definition.definitionId(), policyRequest(true, absent.version()));
        var policyImpact = policies.impact(1L, definition.definitionId(), policyDraft.policyRevisionId());
        var policy = policies.publish(1L, releaser, UUID.randomUUID(), null,
                definition.definitionId(), policyDraft.policyRevisionId(),
                new WidgetRegistryDtos.TenantPolicyPublishRequest(
                        absent.version(), policyImpact.impactRevision(), "TEST", "publish policy"));
        assertThat(policy.current().policyState()).isEqualTo("PUBLISHED");

        var disabledDraft = policies.createRevision(1L, author, UUID.randomUUID(), null,
                definition.definitionId(), policyRequest(false, policy.version()));
        var disabledImpact = policies.impact(
                1L, definition.definitionId(), disabledDraft.policyRevisionId());
        var disabled = policies.publish(1L, releaser, UUID.randomUUID(), null,
                definition.definitionId(), disabledDraft.policyRevisionId(),
                new WidgetRegistryDtos.TenantPolicyPublishRequest(
                        policy.version(), disabledImpact.impactRevision(), "TEST", "disable policy"));
        var rollbackImpact = policies.rollbackImpact(
                1L, definition.definitionId(), policyDraft.policyRevisionId());
        var rolledBack = policies.rollback(1L, releaser, UUID.randomUUID(), null,
                definition.definitionId(), new WidgetRegistryDtos.TenantPolicyRollbackRequest(
                        policyDraft.policyRevisionId(), rollbackImpact.impactRevision(),
                        disabled.version(), "TEST", "rollback policy"));
        assertThat(rolledBack.current().enabled()).isTrue();
        assertThat(rolledBack.current().policyRevisionId())
                .isNotEqualTo(policyDraft.policyRevisionId());

        long registryBeforeBlock = ledger.state().getRegistryRevision();
        var blockImpact = releases.impact(nextPublished.versionId(), "BLOCK");
        UUID blockCommand = UUID.randomUUID();
        OffsetDateTime safetyExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
        var blockRequest = new WidgetRegistryDtos.SafetyTransitionRequest(
                "SECURITY_POLICY", "INC-2026-0915", null, safetyExpiresAt,
                blockImpact.impactRevision(), "TEST", "block release", deprecated.version());
        var blocked = releases.block(releaser, blockCommand, null, nextPublished.versionId(), blockRequest);
        assertThat(blocked.releaseState()).isEqualTo("BLOCKED");
        assertThat(releases.block(releaser, blockCommand, null, nextPublished.versionId(), blockRequest))
                .isEqualTo(blocked);
        assertThat(ledger.state().getRegistryRevision()).isEqualTo(registryBeforeBlock + 1);
        assertThat(jdbc.queryForObject("""
                SELECT request_audit_payload ->> 'internalIncidentRef'
                  FROM plt_widget_command_receipts
                 WHERE actor_id = ? AND command_id = ?
                """, String.class, releaser, blockCommand)).isEqualTo("INC-2026-0915");
        assertThat(jdbc.queryForObject("""
                SELECT after_snapshot ->> 'internalIncidentRef'
                  FROM plt_widget_registry_events
                 WHERE command_id = ?
                """, String.class, blockCommand)).isEqualTo("INC-2026-0915");
        assertThat(jdbc.queryForObject("""
                SELECT request_audit_payload ->> 'reasonText'
                  FROM plt_widget_command_receipts
                 WHERE actor_id = ? AND command_id = ?
                """, String.class, releaser, blockCommand)).isEqualTo("block release");
        assertThat(jdbc.queryForObject("""
                SELECT request_audit_payload ->> 'expiresAt'
                  FROM plt_widget_command_receipts
                 WHERE actor_id = ? AND command_id = ?
                """, String.class, releaser, blockCommand)).isNotBlank();
        assertThatThrownBy(() -> releases.block(releaser, UUID.randomUUID(), null,
                nextPublished.versionId(), new WidgetRegistryDtos.SafetyTransitionRequest(
                        "SECURITY_POLICY", "INC-OTHER", null, null,
                        blockImpact.impactRevision(), "TEST", "repeat block", blocked.version())))
                .isInstanceOf(BaseException.class);

        var quarantineImpact = releases.impact(nextPublished.versionId(), "QUARANTINE");
        var quarantined = releases.quarantine(releaser, UUID.randomUUID(), null,
                nextPublished.versionId(), new WidgetRegistryDtos.SafetyTransitionRequest(
                        "SECURITY_POLICY", "INC-2026-0915", null, null,
                        quarantineImpact.impactRevision(), "TEST", "quarantine release",
                        blocked.version()));
        assertThat(quarantined.safetyState()).isEqualTo("QUARANTINED");
        var revokeImpact = releases.impact(nextPublished.versionId(), "REVOKE");
        var revoked = releases.revoke(releaser, UUID.randomUUID(), null,
                nextPublished.versionId(), new WidgetRegistryDtos.SafetyTransitionRequest(
                        "SECURITY_POLICY", "INC-2026-0915", null, null,
                        revokeImpact.impactRevision(), "TEST", "revoke release",
                        quarantined.version()));
        assertThat(revoked.safetyState()).isEqualTo("REVOKED");
        assertThat(revoked.allowedTransitions()).isEmpty();

        var disable = controls.disable(releaser, UUID.randomUUID(), null,
                new WidgetRegistryDtos.RuntimeDisableRequest(
                        "RUNTIME_RENDER", "TENANT", "1", 1L, null, null,
                        "INCIDENT", "INC-2026-0915", "TEST", "disable tenant runtime", 0L));
        assertThatThrownBy(() -> controls.approveEnable(releaser, UUID.randomUUID(), null,
                disable.controlId(), new WidgetRegistryDtos.RuntimeEnableApprovalRequest(
                        disable.controlRevision(), List.of("incident-reviewed"),
                        "TEST", "self approval", disable.version())))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
        var approval = controls.approveEnable(reviewer, UUID.randomUUID(), null,
                disable.controlId(), new WidgetRegistryDtos.RuntimeEnableApprovalRequest(
                        disable.controlRevision(), List.of("incident-reviewed"),
                        "TEST", "approve enable", disable.version()));
        var enabled = controls.enable(author, UUID.randomUUID(), null, disable.controlId(),
                new WidgetRegistryDtos.RuntimeEnableRequest(
                        approval.approvalId(), disable.controlRevision(),
                        "TEST", "enable", disable.version()));
        assertThat(enabled.state()).isEqualTo("ENABLED");
        assertThatThrownBy(() -> controls.enable(50000L, UUID.randomUUID(), null,
                disable.controlId(), new WidgetRegistryDtos.RuntimeEnableRequest(
                        approval.approvalId(), disable.controlRevision(),
                        "TEST", "reuse approval", enabled.version())))
                .isInstanceOf(BaseException.class);

        UUID expiredControlId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plt_widget_runtime_controls(
                    control_id, provider_product_key, control_scope, target_type, target_id,
                    control_state, control_revision, reason_code, reason_text, incident_ref,
                    expires_at, created_by)
                VALUES (?, 'core.calendar', 'CATALOG_MUTATIONS', 'PROVIDER', 'core.calendar',
                    'DISABLED', 1, 'TEST', 'expired test control', 'INC-EXPIRED',
                    '2020-01-01T00:00:00Z', ?)
                """, expiredControlId, releaser);
        var replacementControl = controls.disable(releaser, UUID.randomUUID(), null,
                new WidgetRegistryDtos.RuntimeDisableRequest(
                        "CATALOG_MUTATIONS", "PROVIDER", "core.calendar", null,
                        "core.calendar", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
                        "INCIDENT", "INC-REPLACEMENT", "TEST", "replace expired control", 0L));
        assertThat(replacementControl.state()).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("""
                SELECT control_state FROM plt_widget_runtime_controls WHERE control_id = ?
                """, String.class, expiredControlId)).isEqualTo("EXPIRED");

        String memberCatalogJson = objectMapper.writeValueAsString(
                catalog.effective(1L, "workspace-home", requiredAuthorities(), "", ""));
        assertThat(memberCatalogJson)
                .doesNotContain("INC-2026-0915", "internalIncidentRef", "reasonText");

        assertThat(audit.list(0, 100).items())
                .extracting(WidgetRegistryDtos.RegistryEventResponse::eventType)
                .contains("WIDGET_DEFINITION_CREATED", "WIDGET_VERSION_PUBLISHED",
                        "WIDGET_CHANNEL_ROLLED_BACK", "WIDGET_VERSION_DEPRECATED",
                        "TENANT_WIDGET_POLICY_ROLLED_BACK", "WIDGET_VERSION_BLOCKED",
                        "WIDGET_VERSION_QUARANTINED", "WIDGET_VERSION_REVOKED",
                        "WIDGET_RUNTIME_DISABLED", "WIDGET_RUNTIME_ENABLED");
    }

    private CertifiedVersion certifyVersion(
            UUID definitionId,
            String definitionKey,
            String semanticVersion,
            UUID predecessorVersionId,
            long author,
            long reviewer) throws Exception {
        var definition = definitions.get(definitionId);
        JsonNode replacementManifest = focusManifest(definitionKey);
        ((com.fasterxml.jackson.databind.node.ObjectNode) replacementManifest.path("operations"))
                .put("freshnessSeconds", 31);
        var draft = definitions.createVersion(author, UUID.randomUUID(), null, definitionId,
                new WidgetRegistryDtos.VersionCreateRequest(
                        semanticVersion, replacementManifest, predecessorVersionId,
                        "TEST", "create replacement version", definition.version()));
        var validation = definitions.validate(author, UUID.randomUUID(), null, draft.versionId(),
                new WidgetRegistryDtos.ValidateRequest(
                        draft.manifestHash(), "TEST", "validate replacement", draft.version()));
        List<UUID> evidenceIds = new ArrayList<>();
        for (String type : List.of(
                "MANIFEST", "SECURITY", "PRIVACY", "A11Y", "PERFORMANCE", "LOCALIZATION")) {
            var current = definitions.getVersion(draft.versionId());
            var evidence = definitions.recordEvidence(reviewer, UUID.randomUUID(), null,
                    draft.versionId(), new WidgetRegistryDtos.EvidenceCreateRequest(
                            type, "PASS", draft.manifestHash(), "replacement-evidence:" + type.toLowerCase(),
                            sha("replacement:" + type), OffsetDateTime.now(ZoneOffset.UTC).plusDays(30),
                            null, "TEST", "replacement certification evidence", current.version()));
            evidenceIds.add(evidence.evidenceId());
        }
        var current = definitions.getVersion(draft.versionId());
        var submitted = definitions.submit(author, UUID.randomUUID(), null, draft.versionId(),
                new WidgetRegistryDtos.WidgetVersionTransitionRequest(
                        "TEST", "submit replacement", current.version()));
        var approved = definitions.decide(reviewer, UUID.randomUUID(), null, draft.versionId(),
                new WidgetRegistryDtos.ReviewDecisionRequest(
                        "APPROVE", validation.validationRunId(), evidenceIds,
                        "TEST", "approve replacement", submitted.version()));
        return new CertifiedVersion(approved, validation.validationRunId(), List.copyOf(evidenceIds));
    }

    private record CertifiedVersion(
            WidgetRegistryDtos.VersionResponse version,
            UUID validationRunId,
            List<UUID> evidenceIds) {}

    private WidgetRegistryDtos.TenantPolicyRevisionRequest policyRequest(
            boolean enabled, long expectedVersion) {
        return new WidgetRegistryDtos.TenantPolicyRevisionRequest(
                enabled, "CHANNEL", "STABLE", null, List.of("workspace-home"),
                allEntitledAudience(), false,
                objectMapper.createObjectNode(), "PRIVATE", "TEST", "tenant policy",
                expectedVersion);
    }

    private JsonNode allEntitledAudience() {
        var audience = objectMapper.createObjectNode()
                .put("schemaVersion", 1)
                .put("mode", "ALL_ENTITLED");
        audience.putArray("roleCodes");
        audience.putArray("groupRefs");
        return audience;
    }

    private String policyProjection(long tenantId) {
        return jdbc.queryForObject("""
                SELECT jsonb_agg(jsonb_build_object(
                           'legacyWidgetKey', d.legacy_widget_key,
                           'enabled', r.enabled,
                           'selector', r.selector_type,
                           'channel', r.channel,
                           'surfaces', r.supported_surface_keys,
                           'audience', r.audience_selector,
                           'required', r.required_widget,
                           'sharingPolicy', r.sharing_policy)
                       ORDER BY d.legacy_widget_key)::text
                  FROM adm_tenant_widget_policy_heads h
                  JOIN adm_tenant_widget_policy_revisions r
                    ON r.policy_revision_id = h.current_revision_id
                  JOIN plt_widget_definitions d ON d.definition_id = h.definition_id
                 WHERE h.tenant_id = ?
                """, String.class, tenantId);
    }

    private String requiredAuthorities() throws Exception {
        JsonNode fixture = objectMapper.readTree(Files.readString(FIXTURE));
        Set<String> authorities = new LinkedHashSet<>();
        fixture.path("fixtures").forEach(item -> item.path("manifest")
                .path("requiredAuthorities").forEach(value -> authorities.add(value.asText())));
        return String.join(",", authorities);
    }

    private void assertShadowCatalogAvailable(long tenantId, String authorities) {
        var response = catalog.effective(tenantId, "workspace-home", authorities, "", "");
        assertThat(response.mode()).isEqualTo("SHADOW");
        assertThat(response.bindingCatalogRevision()).isEqualTo(EXPECTED_BINDING_CATALOG_REVISION);
        assertThat(response.contexts()).hasSize(1);
        assertThat(response.contexts().getFirst().items()).hasSize(20);
        List<WidgetRegistryDtos.EffectiveItem> baseline = response.contexts().getFirst().items()
                .stream().filter(item -> item.definitionId().toString().startsWith("30000000"))
                .toList();
        assertThat(baseline).hasSize(7).allSatisfy(item -> {
            assertThat(item.effectiveState())
                    .isEqualTo(WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE);
            assertThat(item.reasonCodes())
                    .containsExactly(WidgetRegistryDtos.EffectiveCatalogReason.AVAILABLE);
            assertThat(item.placementCapabilities().canAdd()).isFalse();
        });
        assertThat(response.contexts().getFirst().items().stream()
                .filter(item -> item.definitionId().toString().startsWith("36000000"))
                .toList()).hasSize(12).allSatisfy(item ->
                        assertThat(item.effectiveState())
                                .isEqualTo(WidgetRegistryDtos.EffectiveCatalogState.DENY));
        assertThat(response.contexts().getFirst().items().stream()
                .filter(item -> "workplace.booking".equals(item.definitionKey()))
                .toList()).singleElement().satisfies(item ->
                        assertThat(item.effectiveState())
                                .isEqualTo(WidgetRegistryDtos.EffectiveCatalogState.DENY));
        assertThat(response.contexts().getFirst().items())
                .noneMatch(item -> "dwaion.artifact".equals(item.definitionKey()));

        var runtime = catalog.runtimeCatalog(
                tenantId, "workspace-home", authorities, "", "", "CLASSIC");
        assertThat(runtime.registryMode()).isEqualTo("SHADOW");
        assertThat(runtime.bindingRevision()).isEqualTo(EXPECTED_BINDING_CATALOG_REVISION);
        assertThat(runtime.definitions()).hasSize(20).allSatisfy(definition ->
                assertThat(definition.rendererBindingRevision())
                        .isEqualTo(EXPECTED_BINDING_CATALOG_REVISION));

        var mzRuntime = catalog.runtimeCatalog(
                tenantId, "workspace-home", authorities, "", "", "MZ_V1");
        assertThat(mzRuntime.registryMode()).isEqualTo("SHADOW");
        assertThat(mzRuntime.decisionRevision()).isNotEqualTo(runtime.decisionRevision());
        assertThat(mzRuntime.definitions())
                .extracting(WidgetCatalogService.RuntimeDefinition::definitionKey)
                .contains("core.workspace.command-rail", "core.work.focus-balance",
                        "core.calendar.meeting-load");

        var mzEffective = catalog.effective(
                tenantId, "workspace-home", authorities, "", "", "MZ_V1");
        assertThat(mzEffective.hostContext().resolvedHostMode()).isEqualTo("MZ");
        assertThat(mzEffective.contexts())
                .extracting(WidgetRegistryDtos.PlacementContext::placementContext)
                .containsExactly("MZ_PERSONAL", "MZ_GOVERNED");
    }

    private JsonNode focusManifest(String definitionKey) throws Exception {
        JsonNode fixture = objectMapper.readTree(Files.readString(FIXTURE));
        JsonNode manifest = fixture.path("fixtures").get(2).path("manifest").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest)
                .put("definitionKey", definitionKey);
        return manifest;
    }

    private static String sha(String value) {
        return WidgetRegistryCommandReceiptService.fingerprintText(value);
    }

    @TestConfiguration
    static class JacksonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        HomeExperienceService homeExperienceService() {
            HomeExperienceService service = org.mockito.Mockito.mock(HomeExperienceService.class);
            HomeExperienceDtos.HomeExperienceResponse response =
                    org.mockito.Mockito.mock(HomeExperienceDtos.HomeExperienceResponse.class);
            org.mockito.Mockito.when(response.effectiveExperienceVariant()).thenReturn("CLASSIC");
            org.mockito.Mockito.when(response.homePreferenceStore()).thenReturn("LEGACY");
            org.mockito.Mockito.when(response.version()).thenReturn(1L);
            org.mockito.Mockito.when(service.get(org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(response);
            org.mockito.Mockito.when(service.allowedModes(org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(java.util.Set.of("CLASSIC", "FLOW_V1", "MZ_V1"));
            org.mockito.Mockito.when(service.modeEnabled(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(true);
            return service;
        }
    }
}
