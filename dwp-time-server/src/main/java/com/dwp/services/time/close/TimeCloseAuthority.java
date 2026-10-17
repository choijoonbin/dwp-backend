package com.dwp.services.time.close;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

interface TimeCloseOwnerAuthoritySource {
    Optional<VerifiedCloseRequest> verify(
            HttpServletRequest request, TimeCloseOwnerRoute route);

    /** Re-evaluates the immutable originating profile before receipt recovery. */
    Optional<VerifiedCloseRequest> reverifyOrigin(
            HttpServletRequest request,
            TimeCloseOwnerRoute originatingRoute,
            String populationScopeDigest,
            long populationRevision,
            long grantRevision);
}

interface TimeClosePopulationProjectionResolver {
    Optional<Projection> resolve(
            long tenantId, long actorId, String gatewayScopeKey, Instant checkedAt);

    record Projection(
            long tenantId,
            long actorId,
            String gatewayScopeKey,
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            String populationDigest,
            String grantDigest,
            Instant validUntil) {
        public Projection {
            if (tenantId <= 0 || actorId <= 0 || populationRevision <= 0
                    || grantRevision <= 0) {
                throw new IllegalArgumentException("projection identity is invalid");
            }
            TimeCloseModels.requireText(gatewayScopeKey, "gatewayScopeKey");
            TimeCloseModels.requireUuid(populationPublicId, "populationPublicId");
            TimeCloseModels.requireDigest(populationDigest, "populationDigest");
            TimeCloseModels.requireDigest(grantDigest, "grantDigest");
            Objects.requireNonNull(validUntil, "validUntil");
        }
    }
}

@Repository
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcTimeClosePopulationProjectionResolver
        implements TimeClosePopulationProjectionResolver {

    private static final String SET_TENANT =
            "SELECT set_config('dwp.tenant_id', ?, true)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcTimeClosePopulationProjectionResolver(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(10);
    }

    @Override
    public Optional<Projection> resolve(
            long tenantId, long actorId, String gatewayScopeKey, Instant checkedAt) {
        if (tenantId <= 0 || actorId <= 0 || gatewayScopeKey == null
                || checkedAt == null) return Optional.empty();
        return inTenant(tenantId, () -> {
            LocalDate effectiveOn = LocalDate.ofInstant(checkedAt, ZoneOffset.UTC);
            List<Projection> rows = jdbc.query("""
                    SELECT p.population_public_id, p.projection_revision,
                           g.grant_revision, p.source_digest AS population_digest,
                           g.source_digest AS grant_digest, g.valid_to
                      FROM tim_target_population_actor_grants g
                      JOIN tim_target_population_projections p
                        ON p.tenant_id = g.tenant_id
                       AND p.population_public_id = g.population_public_id
                     WHERE g.tenant_id = ? AND g.actor_id = ?
                       AND g.gateway_scope_key = ?
                       AND g.lifecycle_state = 'ACTIVE'
                       AND p.lifecycle_state = 'ACTIVE'
                       AND g.population_revision = p.projection_revision
                       AND g.valid_from <= ?
                       AND (g.valid_to IS NULL OR g.valid_to > ?)
                       AND p.effective_from <= ?
                       AND (p.effective_to IS NULL OR p.effective_to > ?)
                    """, (row, ignored) -> new Projection(
                            tenantId, actorId, gatewayScopeKey,
                            row.getObject("population_public_id", UUID.class),
                            row.getLong("projection_revision"),
                            row.getLong("grant_revision"),
                            row.getString("population_digest").trim(),
                            row.getString("grant_digest").trim(),
                            row.getTimestamp("valid_to") == null
                                    ? Instant.parse("9999-12-31T23:59:59Z")
                                    : row.getTimestamp("valid_to").toInstant()),
                    tenantId, actorId, gatewayScopeKey,
                    Timestamp.from(checkedAt), Timestamp.from(checkedAt),
                    effectiveOn, effectiveOn);
            return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
        });
    }

    private <T> T inTenant(long tenantId, Supplier<T> work) {
        T result = transactions.execute(status -> {
            String expected = Long.toString(tenantId);
            String actual = jdbc.queryForObject(SET_TENANT, String.class, expected);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Database tenant context was not established");
            }
            return work.get();
        });
        return Objects.requireNonNull(result, "population transaction returned null");
    }
}

/** Converts the trusted central v50 decision projection into owner-local predicates. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class GatewayVerifiedTimeCloseOwnerAuthoritySource
        implements TimeCloseOwnerAuthoritySource {

    static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String ACTOR = "X-DWP-User-ID";
    static final String SUBJECT = "X-DWP-Subject-Principal-Public-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String RESPONSIBILITIES = "X-DWP-Responsibilities";
    static final String ROUTE = "X-DWP-Route-Contract-Key";
    static final String CONTEXT = "X-DWP-Context-Key";
    static final String SCOPE = "X-DWP-Context-Scope-Key";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String CURRENT_REVISION = "X-DWP-Current-Decision-Revision";
    static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    static final String EXPECTED_REVISION = "X-DWP-Expected-Decision-Revision";
    static final String CORRELATION = "X-Correlation-ID";
    static final String FIELD_POLICY_REVISION = "X-DWP-Field-Policy-Revision";
    static final String AUTHORIZATION_REVISION = "X-DWP-Authorization-Revision";
    static final String POPULATION_KIND = "X-DWP-Population-Kind";
    static final String PURPOSE = "X-Purpose-Code";
    static final String LEGAL_ENTITY = "X-DWP-Legal-Entity-Public-ID";
    static final String TIME_GROUP = "X-DWP-Time-Group-Public-ID";
    static final String MASKED = "X-DWP-Masked-Field-Groups";
    static final String OMITTED = "X-DWP-Omitted-Field-Groups";
    static final String SUPPORT_SESSION = "X-DWP-Support-Session-ID";
    static final String ROLLOUT_STATE = "X-DWP-Rollout-State";
    static final String ROLLOUT_REVISION = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_COHORT = "X-DWP-Rollout-Cohort";

    private static final Set<String> ACCESS_MODES = Set.of("NORMAL", "ELEVATED");
    private static final Set<String> ROLLOUT_STATES = Set.of("110", "111");
    private static final String RECEIPT_ROUTE =
            "route.hcm.time.command-receipt.data";

    private final String expectedServiceToken;
    private final boolean productAuthorizationEnabled;
    private final Set<Long> enrolledTenants;
    private final Clock clock;
    private final TimeClosePopulationProjectionResolver projections;

    @Autowired
    GatewayVerifiedTimeCloseOwnerAuthoritySource(
            @Value("${dwp.time.service-token:}") String expectedServiceToken,
            @Value("${dwp.time.product-authorization-enabled:false}")
            boolean productAuthorizationEnabled,
            @Value("${dwp.hris.time.close.enrolled-tenants:}") String enrolledTenants,
            TimeClosePopulationProjectionResolver projections) {
        this(expectedServiceToken, productAuthorizationEnabled,
                parseTenants(enrolledTenants), Clock.systemUTC(), projections);
    }

    GatewayVerifiedTimeCloseOwnerAuthoritySource(
            String expectedServiceToken,
            boolean productAuthorizationEnabled,
            Set<Long> enrolledTenants,
            Clock clock,
            TimeClosePopulationProjectionResolver projections) {
        this.expectedServiceToken = expectedServiceToken == null
                ? "" : expectedServiceToken.strip();
        this.productAuthorizationEnabled = productAuthorizationEnabled;
        this.enrolledTenants = Set.copyOf(enrolledTenants);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.projections = Objects.requireNonNull(projections, "projections");
    }

    @Override
    public Optional<VerifiedCloseRequest> verify(
            HttpServletRequest request, TimeCloseOwnerRoute route) {
        if (request == null || route == null || !productAuthorizationEnabled
                || expectedServiceToken.isBlank()) {
            throw unavailable("TIM close authority is not configured");
        }
        if (!constantTimeEquals(expectedServiceToken, single(request, SERVICE_TOKEN, 512))) {
            throw unavailable("Trusted Gateway identity is unavailable");
        }
        Long tenantId = positiveLong(single(request, TENANT, 64));
        Long actorId = positiveLong(single(request, ACTOR, 64));
        if (tenantId == null || actorId == null || !enrolledTenants.contains(tenantId)) {
            return Optional.empty();
        }
        if (request.getHeader(SUPPORT_SESSION) != null) return Optional.empty();

        Instant now = clock.instant();
        String routeKey = single(request, ROUTE, 256);
        String context = single(request, CONTEXT, 128);
        String scope = single(request, SCOPE, 128);
        String access = single(request, ACCESS_MODE, 32);
        String decisionRevision = single(request, CURRENT_REVISION, 80);
        Instant revalidateAt = instant(single(request, REVALIDATE_AT, 80));
        String expectedRevision = single(request, EXPECTED_REVISION, 80);
        UUID correlation = uuid(single(request, CORRELATION, 80));
        UUID subject = uuid(single(request, SUBJECT, 80));
        UUID legalEntity = uuid(single(request, LEGAL_ENTITY, 80));
        UUID timeGroup = uuid(single(request, TIME_GROUP, 80));
        Long fieldRevision = positiveLong(single(request, FIELD_POLICY_REVISION, 64));
        Long authorizationRevision = positiveLong(
                single(request, AUTHORIZATION_REVISION, 64));
        String purpose = single(request, PURPOSE, 100);
        if (!route.routeContractKey().equals(routeKey)
                || context == null || !context.matches("psc-[0-9a-f]{64}")
                || !HcmEligibilityScopeKey.isCanonical(scope)
                || !ACCESS_MODES.contains(access)
                || decisionRevision == null
                || !decisionRevision.matches("psr-[0-9a-f]{64}")
                || revalidateAt == null || !revalidateAt.isAfter(now)
                || correlation == null || subject == null
                || legalEntity == null || timeGroup == null
                || fieldRevision == null || authorizationRevision == null
                || !"TARGET_POPULATION".equals(single(request, POPULATION_KIND, 64))
                || !route.purpose().equals(purpose)
                || !ROLLOUT_STATES.contains(single(request, ROLLOUT_STATE, 16))
                || !validRollout(single(request, ROLLOUT_REVISION, 80),
                        single(request, ROLLOUT_COHORT, 64))) {
            throw unavailable("Current TIM close route authority is stale or mismatched");
        }
        if (route.freshCommandStepUp() && !"ELEVATED".equals(access)) {
            return Optional.empty();
        }
        if (!decisionRevision.equals(expectedRevision)) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                    "Current TIM close decision revision is mismatched");
        }
        Set<String> permissions = tokens(request, PERMISSIONS, 100, true);
        Set<String> responsibilities = tokens(request, RESPONSIBILITIES, 100, false);
        if (permissions == null || !permissions.contains("APP.HCM:VIEW")
                || !route.accepts(permissions, responsibilities)) {
            return Optional.empty();
        }
        TimeClosePopulationProjectionResolver.Projection projection = projections.resolve(
                        tenantId, actorId, scope, now)
                .orElseThrow(() -> unavailable("TIM close population is unavailable"));
        if (projection.tenantId() != tenantId || projection.actorId() != actorId
                || !projection.gatewayScopeKey().equals(scope)
                || !projection.validUntil().isAfter(now)) {
            throw unavailable("TIM close population is stale or mismatched");
        }
        Set<String> masked = optionalTokens(request, MASKED, route.fieldGroups().size());
        Set<String> omitted = optionalTokens(request, OMITTED, route.fieldGroups().size());
        if (!route.fieldGroups().containsAll(masked)
                || !route.fieldGroups().containsAll(omitted)
                || !Collections.disjoint(masked, omitted)
                || (!route.fieldGroups().isEmpty()
                    && omitted.containsAll(route.fieldGroups()))
                || (route.fieldGroups().isEmpty() && (!masked.isEmpty() || !omitted.isEmpty()))) {
            throw unavailable("TIM close field decision is invalid");
        }
        String scopeDigest = TimeCloseDigests.sha256(
                "TimeClosePopulationScope.v1", tenantId,
                projection.populationPublicId(), projection.populationRevision(),
                projection.grantRevision(), projection.populationDigest(),
                projection.grantDigest(), legalEntity, timeGroup);
        Instant effectiveRevalidate = revalidateAt.isBefore(projection.validUntil())
                ? revalidateAt : projection.validUntil();
        return Optional.of(new VerifiedCloseRequest(
                tenantId, actorId, subject, permissions, responsibilities,
                route.action(), route.routeContractKey(), context, scope, access,
                projection.populationPublicId(),
                projection.populationRevision(), projection.grantRevision(),
                scopeDigest, legalEntity, timeGroup, fieldRevision,
                authorizationRevision, decisionRevision, purpose,
                effectiveRevalidate, correlation, masked, omitted));
    }

    Optional<VerifiedTimeCloseReceiptRequest> verifyReceipt(
            HttpServletRequest request) {
        if (request == null || !productAuthorizationEnabled
                || expectedServiceToken.isBlank()) {
            throw unavailable("TIM close receipt authority is not configured");
        }
        if (!constantTimeEquals(expectedServiceToken, single(request, SERVICE_TOKEN, 512))) {
            throw unavailable("Trusted Gateway identity is unavailable");
        }
        Long tenantId = positiveLong(single(request, TENANT, 64));
        Long actorId = positiveLong(single(request, ACTOR, 64));
        if (tenantId == null || actorId == null || !enrolledTenants.contains(tenantId)) {
            return Optional.empty();
        }
        if (request.getHeader(SUPPORT_SESSION) != null) return Optional.empty();

        Instant now = clock.instant();
        String context = single(request, CONTEXT, 128);
        String scope = single(request, SCOPE, 128);
        String access = single(request, ACCESS_MODE, 32);
        String decisionRevision = single(request, CURRENT_REVISION, 80);
        Instant revalidateAt = instant(single(request, REVALIDATE_AT, 80));
        UUID correlation = uuid(single(request, CORRELATION, 80));
        UUID subject = uuid(single(request, SUBJECT, 80));
        UUID legalEntity = uuid(single(request, LEGAL_ENTITY, 80));
        UUID timeGroup = uuid(single(request, TIME_GROUP, 80));
        Long fieldRevision = positiveLong(single(request, FIELD_POLICY_REVISION, 64));
        Long authorizationRevision = positiveLong(
                single(request, AUTHORIZATION_REVISION, 64));
        if (!RECEIPT_ROUTE.equals(single(request, ROUTE, 256))
                || context == null || !context.matches("psc-[0-9a-f]{64}")
                || !HcmEligibilityScopeKey.isCanonical(scope)
                || !ACCESS_MODES.contains(access)
                || decisionRevision == null
                || !decisionRevision.matches("psr-[0-9a-f]{64}")
                || revalidateAt == null || !revalidateAt.isAfter(now)
                || correlation == null || subject == null
                || legalEntity == null || timeGroup == null
                || fieldRevision == null || authorizationRevision == null
                || !"TARGET_POPULATION".equals(single(request, POPULATION_KIND, 64))
                || !"COMMAND_STATUS".equals(single(request, PURPOSE, 100))
                || !ROLLOUT_STATES.contains(single(request, ROLLOUT_STATE, 16))
                || !validRollout(single(request, ROLLOUT_REVISION, 80),
                        single(request, ROLLOUT_COHORT, 64))) {
            throw unavailable("Current TIM close receipt authority is stale or mismatched");
        }
        Set<String> permissions = tokens(request, PERMISSIONS, 100, true);
        Set<String> responsibilities = tokens(request, RESPONSIBILITIES, 100, false);
        if (permissions == null || responsibilities == null
                || !permissions.contains("APP.HCM:VIEW")
                || !permissions.contains("DATA.HRIS_COMMAND_RECEIPT:VIEW")) {
            return Optional.empty();
        }
        if (!optionalTokens(request, MASKED, 1).isEmpty()
                || !optionalTokens(request, OMITTED, 1).isEmpty()) {
            throw unavailable("TIM close receipt field decision is invalid");
        }
        TimeClosePopulationProjectionResolver.Projection projection = projections.resolve(
                        tenantId, actorId, scope, now)
                .orElseThrow(() -> unavailable("TIM close population is unavailable"));
        if (projection.tenantId() != tenantId || projection.actorId() != actorId
                || !projection.gatewayScopeKey().equals(scope)
                || !projection.validUntil().isAfter(now)) {
            throw unavailable("TIM close population is stale or mismatched");
        }
        String scopeDigest = TimeCloseDigests.sha256(
                "TimeClosePopulationScope.v1", tenantId,
                projection.populationPublicId(), projection.populationRevision(),
                projection.grantRevision(), projection.populationDigest(),
                projection.grantDigest(), legalEntity, timeGroup);
        Instant effectiveRevalidate = revalidateAt.isBefore(projection.validUntil())
                ? revalidateAt : projection.validUntil();
        return Optional.of(new VerifiedTimeCloseReceiptRequest(
                tenantId, actorId, subject, permissions, responsibilities,
                projection.populationPublicId(), projection.populationRevision(),
                projection.grantRevision(), scopeDigest, fieldRevision,
                authorizationRevision, decisionRevision, effectiveRevalidate, correlation));
    }

    @Override
    public Optional<VerifiedCloseRequest> reverifyOrigin(
            HttpServletRequest request,
            TimeCloseOwnerRoute originatingRoute,
            String populationScopeDigest,
            long populationRevision,
            long grantRevision) {
        return verify(request, originatingRoute).filter(verified ->
                verified.populationScopeDigest().equals(populationScopeDigest)
                        && verified.populationRevision() == populationRevision
                        && verified.grantRevision() == grantRevision);
    }

    private static boolean validRollout(String revision, String cohort) {
        return revision != null && revision.matches("rollout-[0-9a-f]{64}")
                && cohort != null && cohort.matches("[a-z0-9-]{1,64}");
    }

    private static Set<Long> parseTenants(String value) {
        if (value == null || value.isBlank()) return Set.of();
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            try {
                long tenant = Long.parseLong(token.strip());
                if (tenant <= 0 || !result.add(tenant)) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                throw new IllegalStateException(
                        "dwp.hris.time.close.enrolled-tenants is invalid", invalid);
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> optionalTokens(
            HttpServletRequest request, String name, int maximum) {
        if (request.getHeader(name) == null) return Set.of();
        Set<String> result = tokens(request, name, maximum, false);
        if (result == null) throw unavailable("TIM close field decision is malformed");
        return result;
    }

    private static Set<String> tokens(
            HttpServletRequest request, String name, int maximum, boolean required) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.isEmpty()) return required ? null : Set.of();
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || value.isBlank() || value.length() > 8_000
                || !value.equals(value.strip()) || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) return null;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.strip()) || token.length() > 500
                    || !result.add(token) || result.size() > maximum) return null;
        }
        return Set.copyOf(result);
    }

    private static String single(HttpServletRequest request, String name, int maximum) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                ? null : value;
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static UUID uuid(String value) {
        try {
            UUID parsed = value == null ? null : UUID.fromString(value);
            return parsed == null || parsed.equals(new UUID(0, 0))
                    || !parsed.toString().equals(value) ? null : parsed;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
