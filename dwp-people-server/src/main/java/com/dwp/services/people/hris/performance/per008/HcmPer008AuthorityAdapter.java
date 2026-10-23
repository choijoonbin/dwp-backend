package com.dwp.services.people.hris.performance.per008;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.people.hris.performance.per008.Per008Contracts.FieldDecision;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Revalidates the exact central route evidence and resolves owner-local tuple authority. */
@Component
@ConditionalOnProperty(name = "dwp.hris.performance.feedback.enabled", havingValue = "true")
final class HcmPer008AuthorityAdapter implements Per008AuthorityPort {
    interface OwnerAuthorityDataPort {
        SubjectBinding actorBinding(long tenantId, UUID principalPublicId);
        Set<SubjectBinding> permittedSubjects(long tenantId, String profileKey,
                String scopeDecisionRevision);
        Map<String, FieldDecision> fieldDecisions(long tenantId, String projectionPolicyKey,
                String profileKey, Operation operation);
        boolean tenantPredicateBound(long tenantId);
    }

    private static final Set<String> MODES = Set.of("NORMAL", "ELEVATED");
    private static final Map<Operation, RouteSpec> ROUTES = Map.of(
            Operation.TEAM_QUERY, new RouteSpec("route.hcm.team.performance.data", "DATA", "GET",
                    Map.of("team-performance-reader", profile("hcm.team.workbench.view", true,
                            Set.of("predicate.team-target-population.v1"),
                            Set.of("TARGET_POPULATION"), "per008.iaq-038.fields.v2",
                            "Per008TeamPerformanceProjectionV2")), "TEAM_MANAGEMENT"),
            Operation.REQUEST_OPTIONS, new RouteSpec(
                    "route.hcm.performance.feedback-request-options.data", "DATA", "GET",
                    Map.of("manager-feedback-requester", profile("hcm.team.performance.review", true,
                                    Set.of("predicate.team-target-population.v1",
                                            "predicate.hcm-performance-feedback-request.v1"),
                                    Set.of("OBJECT", "TARGET_POPULATION"),
                                    "per008.feedback-request-options.fields.v1",
                                    "Per008FeedbackRequestOptionsV1"),
                            "self-feedback-requester", profile("per.feedback.request.create", true,
                                    Set.of("predicate.self-person.v1",
                                            "predicate.hcm-performance-feedback-request.v1"),
                                    Set.of("SELF", "OBJECT"),
                                    "per008.feedback-request-options.fields.v1",
                                    "Per008FeedbackRequestOptionsV1")),
                    "PERFORMANCE_FEEDBACK"),
            Operation.REQUEST_CREATE, new RouteSpec(
                    "route.hcm.performance.feedback-request-create.action", "ACTION", "POST",
                    Map.of("manager-feedback-requester", profile("hcm.team.performance.review", false,
                                    Set.of("predicate.team-target-population.v1",
                                            "predicate.hcm-performance-feedback-request.v1"),
                                    Set.of("OBJECT", "TARGET_POPULATION"), null, null),
                            "self-feedback-requester", profile("per.feedback.request.create", false,
                                    Set.of("predicate.self-person.v1",
                                            "predicate.hcm-performance-feedback-request.v1"),
                                    Set.of("SELF", "OBJECT"), null, null)),
                    "PERFORMANCE_FEEDBACK"),
            Operation.ASSIGNMENTS_MINE, new RouteSpec(
                    "route.hcm.personal.feedback-assignments-mine.data", "DATA", "GET",
                    Map.of("self-performance-reader", profile("hcm.personal.performance.view", true,
                            Set.of("predicate.self-person.v1",
                                    "predicate.hcm-performance-feedback-assignment.v1"),
                            Set.of("SELF", "TARGET_POPULATION"),
                            "per008.feedback-assigned-list.fields.v1",
                            "Per008AssignedFeedbackListV1")),
                    "PERFORMANCE_FEEDBACK"),
            Operation.ASSIGNMENT_DETAIL, new RouteSpec(
                    "route.hcm.personal.feedback-assignment-detail.data", "DATA", "GET",
                    Map.of("assigned-feedback-reader", profile("hcm.personal.performance.view", true,
                            Set.of("predicate.hcm-performance-feedback-assignment.v1"),
                            Set.of("OBJECT", "TARGET_POPULATION"),
                            "per008.feedback-assignment-detail.fields.v1",
                            "Per008AssignedFeedbackDetailV1")),
                    "PERFORMANCE_FEEDBACK"),
            Operation.RESPONSE_SUBMIT, new RouteSpec(
                    "route.hcm.personal.feedback-response-submit.action", "ACTION", "POST",
                    Map.of("assigned-feedback-provider", profile("per.feedback.assigned.submit", false,
                            Set.of("predicate.hcm-performance-feedback-assignment.v1"),
                            Set.of("OBJECT", "TARGET_POPULATION"), null, null)),
                    "PERFORMANCE_FEEDBACK"),
            Operation.RELEASE_READ, new RouteSpec(
                    "route.hcm.performance.feedback-release.data", "DATA", "GET",
                    Map.of("self-feedback-release-reader", profile("per.feedback.aggregate.view", true,
                                    Set.of("predicate.hcm-performance-feedback-release.v1"),
                                    Set.of("SELF", "OBJECT"),
                                    "per008.feedback-release.fields.v1", "Per008FeedbackReleaseV1"),
                            "manager-feedback-release-reader", profile("per.feedback.aggregate.view", true,
                                    Set.of("predicate.hcm-performance-feedback-release.v1"),
                                    Set.of("OBJECT", "TARGET_POPULATION"),
                                    "per008.feedback-release.fields.v1", "Per008FeedbackReleaseV1"),
                            "operator-feedback-release-reader", profile("per.feedback.aggregate.view", true,
                                    Set.of("predicate.hcm-performance-feedback-release.v1"),
                                    Set.of("OBJECT", "TARGET_POPULATION"),
                                    "per008.feedback-release.fields.v1", "Per008FeedbackReleaseV1")),
                    "PERFORMANCE_FEEDBACK"));

    private final OwnerAuthorityDataPort data;
    private final AuditOutboxRecorder audit;
    private final Clock clock;
    private final Set<Long> enrolledTenants;

    HcmPer008AuthorityAdapter(ObjectProvider<OwnerAuthorityDataPort> data,
            AuditOutboxRecorder audit,
            @Value("${dwp.hris.performance.feedback.enrolled-tenants:}") String enrolledTenants) {
        this(data.getIfAvailable(), audit, Clock.systemUTC(), parseTenants(enrolledTenants));
    }

    HcmPer008AuthorityAdapter(OwnerAuthorityDataPort data, AuditOutboxRecorder audit, Clock clock,
            Set<Long> enrolledTenants) {
        this.data = data;
        this.audit = audit;
        this.clock = clock;
        this.enrolledTenants = Set.copyOf(enrolledTenants);
    }

    @Override
    public AuthorityEvidence authorize(AuthorityRequest request) {
        RouteSpec expected = request == null ? null : ROUTES.get(request.operation());
        if (expected == null || data == null) {
            throw Per008Problem.unavailable("PER008_AUTHORITY_PROVIDER_UNAVAILABLE");
        }
        PeopleRequestContext.Actor actor;
        HcmPepContext.Evidence evidence;
        try {
            actor = PeopleRequestContext.require();
            evidence = HcmPepContext.require();
        } catch (RuntimeException missing) {
            throw Per008Problem.unauthorized("PER008_TRUST_CONTEXT_REQUIRED");
        }
        if (actor.tenantId() == null || actor.tenantId() < 1 || actor.userId() == null
                || actor.personPublicId() == null || evidence.revalidateAt() == null
                || !evidence.revalidateAt().toInstant().isAfter(clock.instant())
                || evidence.decisionRevision() == null
                || !evidence.decisionRevision().matches("psr-[0-9a-f]{64}")
                || evidence.scopeKey() == null
                || !enrolledTenants.contains(actor.tenantId())
                || evidence.rolloutState() == null
                || !evidence.rolloutState().matches("[01]1[01]")
                || evidence.contextKey() == null
                || !evidence.contextKey().matches("psc-[0-9a-f]{64}")
                || !HcmEligibilityScopeKey.isCanonical(evidence.scopeKey())
                || !expected.purpose().equals(evidence.purpose())) {
            throw Per008Problem.forbidden("PER008_TRUST_CONTEXT_STALE");
        }
        HcmV3PepRegistry.RouteAuthority route = evidence.authority();
        ProfileSpec profile = route == null ? null : expected.profiles().get(route.profileKey());
        if (route == null || !expected.routeKey().equals(route.routeContractKey())
                || !expected.routeKind().equals(route.routeKind())
                || !expected.method().equals(route.method())
                || profile == null
                || !profile.capability().equals(route.capabilityContractKey())
                || profile.readOnly() != route.readOnly()
                || !profile.activeModes().contains(evidence.activeAccessMode())
                || !profile.predicatePolicyKeys().equals(route.predicatePolicyKeys())
                || !profile.targetBindingKinds().equals(route.targetBindingKinds())
                || !java.util.Objects.equals(profile.projectionPolicyKey(),
                        route.projectionPolicyKey())
                || !java.util.Objects.equals(profile.responseSchemaKey(),
                        route.responseSchemaKey())
                || !java.util.Objects.equals(profile.projectionPolicyKey(),
                        evidence.fieldPolicyKey())) {
            throw Per008Problem.forbidden("PER008_EXACT_ROUTE_AUTHORITY_REQUIRED");
        }
        EnumSet<PepLayer> layers = EnumSet.noneOf(PepLayer.class);
        layers.add(PepLayer.GATEWAY_APP_ENTITLEMENT);
        layers.add(PepLayer.OWNER_API_OPERATION);
        layers.add(PepLayer.PURPOSE_POLICY);
        SubjectBinding actorBinding;
        Set<SubjectBinding> population;
        Map<String, FieldDecision> fields;
        try {
            actorBinding = data.actorBinding(actor.tenantId(), actor.personPublicId());
            population = data.permittedSubjects(actor.tenantId(), route.profileKey(),
                    evidence.scopeKey());
            fields = data.fieldDecisions(actor.tenantId(), route.projectionPolicyKey(),
                    route.profileKey(), request.operation());
            if (!data.tenantPredicateBound(actor.tenantId())) {
                throw Per008Problem.unavailable("PER008_TENANT_PREDICATE_UNBOUND");
            }
        } catch (RuntimeException unavailable) {
            throw Per008Problem.unavailable("PER008_OWNER_AUTHORITY_UNAVAILABLE");
        }
        if (actorBinding == null || population == null || fields == null
                || !closedFieldDecisions(request.operation(), fields)
                || (route.readOnly() && (route.projectionPolicyKey() == null
                    || !route.projectionPolicyKey().equals(evidence.fieldPolicyKey())))) {
            throw Per008Problem.unavailable("PER008_OWNER_AUTHORITY_UNAVAILABLE");
        }
        layers.add(PepLayer.REPOSITORY_TENANT_PREDICATE);
        layers.add(PepLayer.FIELD_PROJECTION);
        if ("self-feedback-requester".equals(route.profileKey())) {
            population = Set.of(actorBinding);
        } else if (requiresPopulation(route.profileKey()) && population.isEmpty()) {
            throw Per008Problem.forbidden("PER008_POPULATION_EMPTY");
        }
        layers.add(PepLayer.RESOURCE_POPULATION);
        UUID correlationId = UUID.randomUUID();
        audit.record(AuditEvent.builder()
                .eventId(correlationId)
                .occurredAt(clock.instant())
                .tenantId(actor.tenantId())
                .category("AUTHORIZATION")
                .action("IAQ.OP.038/" + request.operation().name())
                .outcome("SUCCESS")
                .severity("HIGH")
                .riskScore(70)
                .actorType("USER")
                .actorId(String.valueOf(actor.userId()))
                .sourceService("dwp-people-server")
                .sourceModule("hris-performance-per008")
                .targetType("PERFORMANCE_FEEDBACK_AUTHORITY")
                .targetId(request.resourcePublicId() == null ? "collection"
                        : request.resourcePublicId().toString())
                .correlationId(correlationId.toString())
                .policyId(route.routeContractKey())
                .policyDecision("ALLOW")
                .metadata(Map.of("profileKey", route.profileKey(),
                        "purposeCode", expected.purpose()))
                .retentionClass("EXTENDED")
                .build());
        layers.add(PepLayer.AUDIT_DECISION);
        AuthorityEvidence result = new AuthorityEvidence(actor.tenantId(), actor.userId(),
                actor.personPublicId(), actorBinding.workerPublicId(),
                actorBinding.assignmentPublicId(), route.routeContractKey(), route.profileKey(),
                route.capabilityContractKey(), expected.purpose(), evidence.scopeKey(),
                evidence.decisionRevision(), evidence.fieldPolicyKey(), digest(evidence.scopeKey()),
                fields, population,
                layers);
        result.requireAllLayers();
        return result;
    }

    private boolean requiresPopulation(String profile) {
        return !Set.of("self-feedback-requester", "self-performance-reader",
                "assigned-feedback-reader", "assigned-feedback-provider",
                "self-feedback-release-reader").contains(profile);
    }

    private boolean closedFieldDecisions(Operation operation, Map<String, FieldDecision> fields) {
        Set<String> required = switch (operation) {
            case TEAM_QUERY -> Set.of("PERFORMANCE_ASSIGNMENT", "DUE_STATUS",
                    "LIMITED_PROFILE", "PERFORMANCE_FEEDBACK_ACTION_TARGET");
            case REQUEST_OPTIONS -> Set.of("PERFORMANCE_FEEDBACK_TEMPLATE_OPTION",
                    "PERFORMANCE_FEEDBACK_POLICY_LIMIT");
            case REQUEST_CREATE -> Set.of("LIMITED_PROFILE", "PERFORMANCE_ASSIGNED",
                    "PERFORMANCE_FEEDBACK_ASSIGNED");
            case ASSIGNMENTS_MINE, ASSIGNMENT_DETAIL, RESPONSE_SUBMIT ->
                    Set.of("PERFORMANCE_FEEDBACK_ASSIGNED");
            case RELEASE_READ -> Set.of("PERFORMANCE_FEEDBACK_AGGREGATE");
        };
        return fields.keySet().equals(required)
                && fields.values().stream().allMatch(java.util.Objects::nonNull);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Set<Long> parseTenants(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            return java.util.Arrays.stream(value.split(","))
                    .map(String::trim).filter(part -> !part.isBlank())
                    .map(Long::parseLong).filter(id -> id > 0)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (RuntimeException invalid) {
            return Set.of();
        }
    }

    private static ProfileSpec profile(String capability, boolean readOnly,
            Set<String> predicates, Set<String> targets, String projection, String schema) {
        return new ProfileSpec(capability, readOnly, MODES, predicates, targets,
                projection, schema);
    }

    private record ProfileSpec(
            String capability,
            boolean readOnly,
            Set<String> activeModes,
            Set<String> predicatePolicyKeys,
            Set<String> targetBindingKinds,
            String projectionPolicyKey,
            String responseSchemaKey) { }

    private record RouteSpec(String routeKey, String routeKind, String method,
            Map<String, ProfileSpec> profiles, String purpose) { }
}
