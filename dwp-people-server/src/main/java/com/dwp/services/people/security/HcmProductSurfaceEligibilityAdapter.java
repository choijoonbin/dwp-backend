package com.dwp.services.people.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single production owner-service evaluator for HCM relationship and
 * target-population evidence. Candidate keys are opaque Auth selectors; every
 * selector is recomputed for the current tenant, actor, product and surface.
 */
@Component
public class HcmProductSurfaceEligibilityAdapter
        implements ProductSurfaceEligibilityPort {

    private static final String PRODUCT = "hcm";
    private static final String SELF = "SELF";
    private static final String TEAM = "TEAM/ORG_UNIT";
    private static final String TEAM_TARGET =
            "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION";
    private static final String OPERATIONS = "ORG_UNIT/LEGAL_ENTITY";
    private static final String EXPORT = "APPROVED_EXPORT_POPULATION";
    private static final String CONFIG = "RS_HCM_CONFIG";
    private static final Set<String> DOMAIN_POPULATIONS = Set.of(
            "WORKFORCE_TARGET_POPULATION",
            "TIME_TARGET_POPULATION",
            "ABSENCE_TARGET_POPULATION",
            "BENEFITS_TARGET_POPULATION",
            "PAY_TARGET_POPULATION",
            "TALENT_TARGET_POPULATION");

    private final HcmPopulationRepository repository;
    private final HcmPopulationScopeService populations;

    public HcmProductSurfaceEligibilityAdapter(
            HcmPopulationRepository repository,
            HcmPopulationScopeService populations) {
        this.repository = repository;
        this.populations = populations;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductSurfaceEligibilityDtos.EligibilityResult evaluate(
            ProductSurfaceEligibilityDtos.EvaluateRequest request) {
        if (!PRODUCT.equals(request.productKey())
                || !Set.of("hcm.personal", "hcm.team", "hcm.operations", "hcm.management")
                        .contains(request.surfaceKey())) {
            return denied(ProductSurfaceEligibilityDtos.Decision.SURFACE_DENIED,
                    "HCM_SURFACE_NOT_OWNED", "surface", "surface");
        }
        if (request.activeAccessMode()
                == ProductSurfaceEligibilityDtos.AccessMode.PROVIDER_SUPPORT) {
            // The eligibility endpoint intentionally receives no support-session token.
            // Support decisions therefore remain in the gateway/auth support authority path.
            return denied(ProductSurfaceEligibilityDtos.Decision.SURFACE_DENIED,
                    "HCM_SUPPORT_OWNER_EVIDENCE_REQUIRED", "support", "support");
        }

        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        Map<String, ResolvedScope> resolved = new LinkedHashMap<>();
        for (ProductSurfaceEligibilityDtos.CandidateScope candidate
                : request.candidateScopes()) {
            resolveCandidate(request, actor, candidate).ifPresent(value ->
                    resolved.putIfAbsent(candidate.key(), value));
        }
        if (resolved.isEmpty()) {
            return denied(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID,
                    "HCM_TARGET_POPULATION_UNAVAILABLE", "relationship-none",
                    tenantRevision(actor.tenantId()));
        }

        List<MaterializedScope> materialized = resolved.entrySet().stream()
                .map(entry -> materialize(request, actor, entry.getKey(), entry.getValue()))
                .toList();
        if (request.contextScopeKey() != null && materialized.stream()
                .noneMatch(value -> value.key().equals(request.contextScopeKey()))) {
            return denied(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID,
                    "HCM_SCOPE_CONTEXT_EXPIRED", aggregate(materialized, true),
                    aggregate(materialized, false));
        }

        String selected = request.contextScopeKey() == null
                ? materialized.getFirst().key() : request.contextScopeKey();
        OffsetDateTime validUntil = request.evaluatedAt().plusSeconds(30);
        List<ProductSurfaceEligibilityDtos.EligibleScope> scopes = new ArrayList<>();
        for (MaterializedScope value : materialized) {
            scopes.add(new ProductSurfaceEligibilityDtos.EligibleScope(
                    value.sourceScopeKey(), value.key(), value.kind(), value.displayName(),
                    value.key().equals(selected), value.readOnly(), validUntil));
        }
        String relationshipRevision = aggregate(materialized, true);
        String populationRevision = aggregate(materialized, false);
        return new ProductSurfaceEligibilityDtos.EligibilityResult(
                ProductSurfaceEligibilityDtos.Decision.ALLOWED,
                null,
                relationshipRevision,
                populationRevision,
                scopes,
                request.evaluatedAt().plusSeconds(20),
                "hcm-evidence-" + digest(relationshipRevision + '\n' + populationRevision)
                        .substring(0, 24));
    }

    private Optional<ResolvedScope> resolveCandidate(
            ProductSurfaceEligibilityDtos.EvaluateRequest request,
            PeopleRequestContext.Actor actor,
            ProductSurfaceEligibilityDtos.CandidateScope candidate) {
        return switch (request.surfaceKey()) {
            case "hcm.personal" -> match(request, candidate, SELF, "SELF")
                    ? repository.actor(actor.tenantId(), actor.personPublicId())
                    .map(value -> new ResolvedScope(
                            value.revision(), "self:" + value.personId() + ':' + value.revision(),
                            "SELF", "Self", false))
                    : Optional.empty();
            case "hcm.team" -> matchAny(request, candidate, "TARGET_POPULATION", TEAM, TEAM_TARGET)
                    ? populations.findTeam().map(value -> new ResolvedScope(
                            value.relationshipRevision(), value.targetPopulationRevision(),
                            "TARGET_POPULATION", value.scope().dataBoundary().name(), false))
                    : Optional.empty();
            case "hcm.operations" -> matchOperations(request, candidate)
                    ? populations.findOperations("READ").map(value -> new ResolvedScope(
                            value.relationshipRevision(), value.targetPopulationRevision(),
                            "TARGET_POPULATION", value.scope().dataBoundary().name(), false))
                    : Optional.empty();
            case "hcm.management" -> resolveManagement(request, actor, candidate);
            default -> Optional.empty();
        };
    }

    private Optional<ResolvedScope> resolveManagement(
            ProductSurfaceEligibilityDtos.EvaluateRequest request,
            PeopleRequestContext.Actor actor,
            ProductSurfaceEligibilityDtos.CandidateScope candidate) {
        if (match(request, candidate, CONFIG, "RESOURCE_SET")) {
            return repository.tenantRevision(actor.tenantId()).map(revision ->
                    new ResolvedScope("auth-responsibility", "config:" + revision,
                            "RESOURCE_SET", "HCM configuration", false));
        }
        if (!match(request, candidate, EXPORT, "TARGET_POPULATION")) {
            return Optional.empty();
        }
        return populations.findOperations("EXPORT").map(value -> new ResolvedScope(
                value.relationshipRevision(), value.targetPopulationRevision(),
                "TARGET_POPULATION", value.scope().dataBoundary().name(), false));
    }

    private boolean matchOperations(
            ProductSurfaceEligibilityDtos.EvaluateRequest request,
            ProductSurfaceEligibilityDtos.CandidateScope candidate) {
        if (!"TARGET_POPULATION".equals(candidate.kind())) return false;
        if (match(request, candidate, OPERATIONS, candidate.kind())) return true;
        return DOMAIN_POPULATIONS.stream()
                .anyMatch(source -> match(request, candidate, source, candidate.kind()));
    }

    private boolean matchAny(
            ProductSurfaceEligibilityDtos.EvaluateRequest request,
            ProductSurfaceEligibilityDtos.CandidateScope candidate,
            String kind,
            String... sources) {
        if (!kind.equals(candidate.kind())) return false;
        for (String source : sources) {
            if (match(request, candidate, source, kind)) return true;
        }
        return false;
    }

    private boolean match(
            ProductSurfaceEligibilityDtos.EvaluateRequest request,
            ProductSurfaceEligibilityDtos.CandidateScope candidate,
            String source,
            String kind) {
        return kind.equals(candidate.kind()) && candidate.key().equals(ProductSurfaceScopeKey.key(
                request.tenantId(), request.actorId(), request.productKey(),
                request.surfaceKey(), source, kind));
    }

    private MaterializedScope materialize(
            ProductSurfaceEligibilityDtos.EvaluateRequest request,
            PeopleRequestContext.Actor actor,
            String sourceScopeKey,
            ResolvedScope value) {
        return new MaterializedScope(
                sourceScopeKey,
                HcmEligibilityScopeKeys.derived(
                        actor.tenantId(), actor.userId(), request.surfaceKey(), sourceScopeKey,
                        value.relationshipRevision(), value.populationRevision()),
                value.kind(), value.displayName(), value.readOnly(),
                value.relationshipRevision(), value.populationRevision());
    }

    private ProductSurfaceEligibilityDtos.EligibilityResult denied(
            ProductSurfaceEligibilityDtos.Decision decision,
            String reason,
            String relationshipRevision,
            String populationRevision) {
        return new ProductSurfaceEligibilityDtos.EligibilityResult(
                decision, reason, relationshipRevision, populationRevision,
                List.of(), null, null);
    }

    private String tenantRevision(Long tenantId) {
        return repository.tenantRevision(tenantId).orElse("tenant-empty");
    }

    private String aggregate(List<MaterializedScope> values, boolean relationship) {
        String material = values.stream()
                .map(value -> relationship
                        ? value.relationshipRevision() : value.populationRevision())
                .distinct().sorted().reduce((left, right) -> left + '\n' + right)
                .orElse("none");
        return (relationship ? "hcm-rel-" : "hcm-pop-")
                + digest(material).substring(0, 40);
    }

    private String digest(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record ResolvedScope(
            String relationshipRevision,
            String populationRevision,
            String kind,
            String displayName,
            boolean readOnly) {
    }

    private record MaterializedScope(
            String sourceScopeKey,
            String key,
            String kind,
            String displayName,
            boolean readOnly,
            String relationshipRevision,
            String populationRevision) {
    }
}
