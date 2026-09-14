package com.dwp.services.approval.systemslaauthority;

import com.dwp.services.approval.domain.ApprovalSystemSlaNativeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class SystemSlaSourceConfiguration {
    private static final String SOURCE = "dwp.approval.system-sla.source.";
    private static final String NOTIFICATION = "dwp.approval.system-sla.notification.";
    @Bean SystemSlaJson systemSlaJson(ObjectMapper mapper) { return new SystemSlaJson(mapper); }
    @Bean ApprovalSystemSlaNativeSource approvalSystemSlaNativeSource(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        return new ApprovalSystemSlaNativeSource(jdbc, mapper);
    }
    @Bean @Lazy SystemSlaSourceKeys systemSlaSourceKeys(SystemSlaJson json, ConfigurableEnvironment env) {
        return new SystemSlaSourceKeys(json, env.getProperty(SOURCE + "owner-private-jwk"), env.getProperty(SOURCE + "transport-private-jwk"),
                env.getProperty(SOURCE + "attestation-public-jwks"), foreignKeys(env, SOURCE));
    }
    @Bean @Lazy SystemSlaNotificationKeys systemSlaNotificationKeys(SystemSlaJson json, SystemSlaSourceKeys source, ConfigurableEnvironment env) {
        var prohibited = new ArrayList<>(source.publicKeys());
        try {
            for (String raw : foreignKeys(env, NOTIFICATION)) {
                if (raw.startsWith("kid:")) prohibited.add(new com.nimbusds.jose.jwk.RSAKey.Builder(source.publicKeys().getFirst()).keyID(raw.substring(4)).build());
                else prohibited.addAll(SystemSlaSourceKeys.foreign(json, raw));
            }
            return new SystemSlaNotificationKeys(json, env.getProperty(NOTIFICATION + "transport-public-jwks"),
                    env.getProperty(NOTIFICATION + "attestation-private-jwk"), env.getProperty(NOTIFICATION + "attestation-public-jwks"), prohibited);
        } catch (Exception invalid) { throw SystemSlaJson.unavailable(); }
    }
    @Bean @Lazy SystemSlaSourceProofIssuer systemSlaSourceProofIssuer(SystemSlaJson json, SystemSlaSourceKeys keys) {
        return new SystemSlaSourceProofIssuer(json, keys, Clock.systemUTC());
    }
    @Bean @Lazy AuthApprovalSystemSlaAuthorityClient authApprovalSystemSlaAuthorityClient(SystemSlaJson json, SystemSlaSourceKeys keys, Environment env) {
        String base = env.getProperty(SOURCE + "auth-base-url");
        try {
            if (base == null || base.endsWith("/")) throw SystemSlaJson.unavailable();
            return new AuthApprovalSystemSlaAuthorityClient(URI.create(base + SystemSlaSourceProtocol.PATH),
                    new SystemSlaSourceAttestationVerifier(json, keys, Clock.systemUTC()));
        } catch (RuntimeException failure) { throw SystemSlaJson.unavailable(); }
    }
    @Bean @Lazy SystemSlaNotificationVerifier systemSlaNotificationVerifier(SystemSlaJson json, SystemSlaNotificationKeys keys) {
        return new SystemSlaNotificationVerifier(json, keys, Clock.systemUTC());
    }
    @Bean @Lazy SystemSlaNotificationAttestationIssuer systemSlaNotificationAttestationIssuer(SystemSlaJson json, SystemSlaNotificationKeys keys) {
        return new SystemSlaNotificationAttestationIssuer(json, keys, Clock.systemUTC());
    }
    @Bean SystemSlaCurrentSource systemSlaCurrentSource(ApprovalSystemSlaNativeSource nativeSource, ObjectProvider<SystemSlaSourceProofIssuer> issuer,
            ObjectProvider<AuthApprovalSystemSlaAuthorityClient> client, ObjectProvider<SystemSlaNotificationVerifier> verifier,
            ObjectProvider<SystemSlaNotificationAttestationIssuer> notificationIssuer, PlatformTransactionManager manager, Environment env) {
        var tx = new TransactionTemplate(manager); tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new SystemSlaCurrentSource(nativeSource, issuer::getObject, client::getObject, verifier::getObject, notificationIssuer::getObject,
                tx, Clock.systemUTC(), env.getProperty(SOURCE + "enabled", Boolean.class, false));
    }
    @Bean FilterRegistrationBean<SystemSlaNotificationFilter> systemSlaNotificationFilter(SystemSlaCurrentSource source,
            SystemSlaNotificationController controller, ObjectMapper mapper) {
        var registration = new FilterRegistrationBean<>(new SystemSlaNotificationFilter(source, controller, mapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5); registration.addUrlPatterns("/*"); return registration;
    }
    private static List<String> foreignKeys(ConfigurableEnvironment env, String excluded) {
        var names = new TreeSet<String>();
        for (var propertySource : env.getPropertySources()) if (propertySource instanceof EnumerablePropertySource<?> source)
            Collections.addAll(names, source.getPropertyNames());
        var result = new ArrayList<String>();
        for (String name : names) {
            String normalized = name.toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
            if (!normalized.startsWith("dwp.") || normalized.startsWith(excluded.replace('-', '.')) || !normalized.matches(".*(key|jwk|jwks|pem|trusted).*")) continue;
            String raw = env.getProperty(name, "");
            if (normalized.matches(".*key[.-]?id$") && !raw.isBlank()) result.add("kid:" + raw);
            else if (raw.stripLeading().startsWith("{") || raw.startsWith("-----BEGIN ")) result.add(raw);
        }
        return List.copyOf(result);
    }
}
