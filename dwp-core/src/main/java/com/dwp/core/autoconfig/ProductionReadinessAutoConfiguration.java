package com.dwp.core.autoconfig;

import static com.dwp.core.autoconfig.ProductionReadinessChecks.production;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireAcr;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireAssertionSecretBase64;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireAudiences;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireBoundServiceSecrets;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireCallback;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireCredential;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireDurationRange;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireEventTransportWhenEnabled;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireExact;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireFalse;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireHostAllowlist;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireKeyId;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireLongRange;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireNotificationAppViewBindings;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireProductionEndpoint;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireProductionHost;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireProductionKafka;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireProductionSecret;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireProductionUri;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireRsaPrivateKey;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireRsaPublicKey;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireSecret;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireTrue;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireTrueWhenEnabled;
import static com.dwp.core.autoconfig.ProductionReadinessChecks.requireUrl;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@AutoConfiguration
public class ProductionReadinessAutoConfiguration {

    private static final String LOCAL_JWT_SECRET =
            "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256";
    private static final String PRODUCT_SURFACE_ROLLOUT_TOPIC =
            "dwp.feature-rollout.decision.changed.v1";

    @Bean
    ApplicationRunner dwpProductionReadinessGuard(Environment environment) {
        return ignored -> {
            if (!production(environment)) return;
            String service = environment.getProperty("spring.application.name", "unknown");
            List<String> failures = new ArrayList<>();
            failures.addAll(LocalBootstrapProductionGuard.violations(environment));
            requireFalse(environment, failures, "otel.sdk.disabled");
            requireFalse(environment, failures, "springdoc.api-docs.enabled");
            requireUrl(environment, failures, "otel.exporter.otlp.endpoint", false,
                    "http://localhost:4318");
            requireUrl(environment, failures, "dwp.audit.collector-url", false);
            requireSecret(environment, failures, "dwp.audit.ingest-token");
            requireTrue(environment, failures, "dwp.observability.api-history.enabled");
            requireUrl(environment, failures, "dwp.observability.api-history.collector-url", false);
            requireSecret(environment, failures, "dwp.observability.api-history.ingest-token");
            requireSecret(environment, failures, "dwp.observability.api-history.privacy-hash-secret");
            requireEventTransportWhenEnabled(environment, failures);
            switch (service) {
                case "dwp-auth-server" -> {
                    requireSecret(environment, failures, "jwt.secret", LOCAL_JWT_SECRET);
                    requireSecret(environment, failures, "dwp.auth.product-surface-token");
                    requireSecret(
                            environment, failures,
                            "dwp.auth.meeting-followup-authority-token",
                            "dwp-local-meeting-followup-authority-token-change-outside-local");
                    requireProductionSecret(
                            environment, failures, "dwp.auth.approval-recovery-token");
                    requireTrue(environment, failures, "dwp.security.session.cookie-secure");
                    requireSecret(environment, failures, "dwp.identity-sync.token");
                    requireSecret(environment, failures, "dwp.provider.provisioning-token");
                    requireSecret(environment, failures, "dwp.scim.cursor-secret",
                            "local-development-scim-cursor-secret-change-me");
                    requireFalse(environment, failures, "dwp.auth.oidc.allow-unlisted-hosts");
                    requireRsaPrivateKey(environment, failures,
                            "dwp.auth.step-up.private-key-pem");
                    requireProductionUri(environment, failures, "dwp.auth.step-up.issuer");
                    requireKeyId(environment, failures, "dwp.auth.step-up.key-id");
                    requireAcr(environment, failures, "dwp.auth.step-up.required-acr");
                    requireAudiences(environment, failures,
                            "dwp.auth.step-up.allowed-audiences");
                    requireLongRange(environment, failures,
                            "dwp.auth.step-up.maximum-authentication-age-seconds", 60, 3600);
                    requireLongRange(environment, failures,
                            "dwp.auth.step-up.challenge-ttl-seconds", 1, 900);
                    requireLongRange(environment, failures,
                            "dwp.auth.step-up.assurance-clock-skew-seconds", 0, 60);
                    requireHostAllowlist(environment, failures, "dwp.auth.oidc.allowed-hosts");
                    Set<String> callbackHosts = requireHostAllowlist(
                            environment, failures, "dwp.auth.oidc.allowed-callback-hosts");
                    requireUrl(environment, failures, "dwp.scim.base-url", true,
                            "http://localhost:8080/scim/v2");
                    requireUrl(environment, failures, "sso.callback-url", true,
                            "http://localhost:4200/auth/oidc/callback");
                    requireCallback(environment, failures, "sso.callback-url", callbackHosts);
                }
                case "dwp-platform-server" -> {
                    requireSecret(environment, failures, "dwp.platform.service-token");
                    requireSecret(environment, failures, "dwp.platform.runtime-service-token");
                    requireSecret(environment, failures, "dwp.identity-sync.token");
                    requireSecret(environment, failures, "dwp.platform.api-history.cursor-secret");
                    requireSecret(environment, failures, "dwp.platform.audit.integrity-secret");
                    requireSecret(environment, failures, "dwp.platform.productivity.data-key");
                    requireTrue(environment, failures,
                            "dwp.platform.product-authorization-approvals-v2-enabled");
                    requireTrueWhenEnabled(
                            environment,
                            failures,
                            "dwp.platform.product-surface-telemetry.collection-enabled",
                            "dwp.platform.product-surface-telemetry.maintenance-enabled");
                    requireProductionEndpoint(
                            environment, failures,
                            "DWP_WORK_MEETING_SOURCE_BASE_URL", "https");
                    requireFalse(
                            environment, failures,
                            "DWP_WORK_MEETING_SOURCE_ALLOW_HTTP");
                    requireKeyId(
                            environment, failures,
                            "DWP_WORK_MEETING_ASSERTION_KEY_ID");
                    requireAssertionSecretBase64(
                            environment, failures,
                            "DWP_WORK_MEETING_ASSERTION_SECRET_BASE64");
                }
                case "dwp-people-server" -> {
                        requireSecret(environment, failures, "dwp.people.service-token");
                    requireSecret(environment, failures, "dwp.people.cursor-secret");
                    requireFalse(environment, failures, "dwp.people.hris.allow-unlisted-hosts");
                }
                case "dwp-provider-server" -> {
                    requireSecret(environment, failures, "dwp.provider.service-token");
                    requireSecret(environment, failures, "dwp.provider.provisioning-token");
                    requireSecret(environment, failures, "dwp.provider.support-validation-token");
                    requireTrue(environment, failures, "dwp.provider.support-cookie-secure");
                    requireFalse(environment, failures,
                            "dwp.provider.local-approval-fixtures-enabled");
                    requireTrue(environment, failures,
                            "dwp.provider.product-surface-rollout.relay-enabled");
                    requireTrue(environment, failures,
                            "dwp.provider.product-surface-rollout.publisher-enabled");
                    requireExact(environment, failures,
                            "dwp.provider.product-surface-rollout.topic",
                            PRODUCT_SURFACE_ROLLOUT_TOPIC);
                    requireProductionKafka(environment, failures);
                }
                case "dwp-approval-server" -> {
                    requireSecret(environment, failures, "dwp.approval.service-token");
                    requireSecret(environment, failures, "dwp.approval.runtime-service-token");
                    requireTrue(environment, failures,
                            "dwp.approval.product-authorization-v2-enabled");
                    requireRsaPublicKey(environment, failures,
                            "dwp.approval.step-up.public-key-pem");
                    requireProductionUri(environment, failures,
                            "dwp.approval.step-up.issuer");
                    requireExact(environment, failures,
                            "dwp.approval.step-up.audience", "dwp-approval-server");
                    requireKeyId(environment, failures, "dwp.approval.step-up.key-id");
                    requireAcr(environment, failures, "dwp.approval.step-up.required-acr");
                    requireLongRange(environment, failures,
                            "dwp.approval.step-up.maximum-authentication-age-seconds", 60, 3600);
                    requireLongRange(environment, failures,
                            "dwp.approval.step-up.maximum-challenge-ttl-seconds", 1, 900);
                }
                case "dwp-messaging-server" ->
                    requireSecret(environment, failures, "dwp.messaging.service-token");
                case "dwp-space-server" -> {
                    requireProductionSecret(
                            environment, failures, "dwp.space.service-token");
                    requireProductionSecret(
                            environment, failures, "dwp.space.identity-sync-token");
                    requireTrue(
                            environment, failures, "dwp.space.entitlement-sync-enabled");
                    requireProductionEndpoint(
                            environment, failures, "dwp.services.auth-url", "https");
                }
                case "dwp-notification-server" -> {
                    requireProductionSecret(
                            environment, failures, "dwp.notification.service-token");
                    requireProductionSecret(
                            environment, failures, "dwp.notification.cursor-secret");
                    requireProductionSecret(
                            environment, failures, "dwp.identity-sync.token");
                    requireProductionEndpoint(
                            environment, failures, "dwp.identity-sync.auth-url", "https");
                    requireNotificationAppViewBindings(environment, failures);
                    requireExact(
                            environment, failures,
                            "dwp.notification.gateway-source", "dwp-gateway");
                    requireBoundServiceSecrets(
                            environment,
                            failures,
                            "dwp.notification.allowed-producers",
                            "dwp.notification.producer-tokens",
                            "dwp.notification.service-token");
                    requireTrue(
                            environment, failures,
                            "dwp.notification.realtime.redis-enabled");
                    requireProductionHost(environment, failures, "spring.data.redis.host");
                    requireProductionSecret(
                            environment, failures, "spring.data.redis.password");
                    requireTrue(environment, failures, "spring.data.redis.ssl.enabled");
                    requireTrue(environment, failures, "dwp.notification.outbox.enabled");
                    requireFalse(
                            environment, failures,
                            "dwp.notification.outbox.provision-topic");
                    requireTrue(
                            environment, failures,
                            "dwp.notification.domain-events.enabled");
                    requireTrue(environment, failures, "dwp.notification.retention.enabled");
                    requireTrue(
                            environment, failures,
                            "dwp.notification.reconciliation.enabled");
                    requireProductionKafka(environment, failures);
                }
                case "dwp-meeting-server" -> {
                    requireSecret(environment, failures, "dwp.meeting.service-token");
                    requireExact(
                            environment, failures,
                            "dwp.meeting.followup-authority.provider",
                            "auth-product-surface");
                    requireSecret(
                            environment, failures,
                            "dwp.meeting.followup-authority.service-token",
                            "dwp-local-meeting-followup-authority-token-change-outside-local");
                    requireKeyId(
                            environment, failures,
                            "DWP_MEETING_WORK_ASSERTION_KEY_ID");
                    requireAssertionSecretBase64(
                            environment, failures,
                            "DWP_MEETING_WORK_ASSERTION_SECRET_BASE64");
                    requireFalse(
                            environment, failures,
                            "dwp.meeting.followup-authority.allow-http");
                    requireProductionEndpoint(
                            environment, failures,
                            "dwp.meeting.followup-authority.base-url",
                            "https");
                    requireExact(environment, failures, "dwp.meeting.provider", "livekit");
                    requireProductionEndpoint(
                            environment, failures, "dwp.meeting.livekit.client-url", "wss");
                    requireProductionEndpoint(
                            environment, failures, "dwp.meeting.livekit.api-url", "https");
                    requireCredential(environment, failures, "dwp.meeting.livekit.api-key", 8);
                    requireSecret(environment, failures, "dwp.meeting.livekit.api-secret");
                    requireDurationRange(
                            environment, failures, "dwp.meeting.token-ttl", 60, 600);
                    requireLongRange(
                            environment, failures, "dwp.meeting.join-code-length", 10, 16);
                    requireExact(
                            environment, failures, "dwp.meeting.recording-policy", "NEVER");
                }
                default -> failures.add("unsupported production service identity: " + service);
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException(
                        "Production readiness checks failed for " + service + ": "
                                + String.join(", ", failures));
            }
        };
    }

}
