package com.dwp.services.people.hr;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.security.HcmEligibilityScopeKeys;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/** Resolves live HCM team and delegated target populations without role inference. */
@Service
public class HcmPopulationScopeService {

    private final HcmPopulationRepository repository;
    private final WorkforceAccessPolicyService accessPolicies;

    public HcmPopulationScopeService(
            HcmPopulationRepository repository,
            WorkforceAccessPolicyService accessPolicies) {
        this.repository = repository;
        this.accessPolicies = accessPolicies;
    }

    public Optional<ResolvedPopulation> findTeam() {
        return findTeam(false);
    }

    private Optional<ResolvedPopulation> findTeam(boolean lockPolicy) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        WorkforceAccessPolicyService.Decision delegated = (lockPolicy
                ? accessPolicies.findForMutation("READ") : accessPolicies.find("READ"))
                .filter(value -> value.field("DIRECTORY"))
                .orElse(null);
        Optional<HcmPopulationRepository.ActorWorkforce> workforce = lockPolicy
                ? repository.actorForMutation(actor.tenantId(), actor.personPublicId())
                : repository.actor(actor.tenantId(), actor.personPublicId());
        if (workforce.isEmpty()) return Optional.empty();
        HcmPopulationRepository.ActorWorkforce manager = workforce.get();
        HcmPopulationRepository.PopulationScope scope = new HcmPopulationRepository.PopulationScope(
                manager.workerId(), manager.assignmentKey(),
                delegated != null && delegated.tenantWide(),
                delegated == null ? Set.of() : delegated.organizationIds(),
                delegated == null
                        ? Set.of("DIRECTORY", "EMPLOYMENT") : delegated.fieldGroups(),
                delegated == null ? "DIRECT_REPORT" : delegated.fingerprint());
        return repository.populationEvidence(actor.tenantId(), scope)
                .map(evidence -> new ResolvedPopulation(manager, scope, evidence));
    }

    public Optional<ResolvedPopulation> findOperations(String action) {
        return findOperations(action, false);
    }

    private Optional<ResolvedPopulation> findOperations(String action, boolean lockPolicy) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        WorkforceAccessPolicyService.Decision decision = (lockPolicy
                ? accessPolicies.findForMutation(action) : accessPolicies.find(action))
                .filter(value -> value.field("DIRECTORY"))
                .orElse(null);
        if (decision == null) return Optional.empty();
        HcmPopulationRepository.ActorWorkforce workforce = (lockPolicy
                ? repository.actorForMutation(actor.tenantId(), actor.personPublicId())
                : repository.actor(actor.tenantId(), actor.personPublicId())).orElse(null);
        HcmPopulationRepository.PopulationScope scope = new HcmPopulationRepository.PopulationScope(
                workforce == null ? 0L : workforce.workerId(), null,
                decision.tenantWide(), decision.organizationIds(), decision.fieldGroups(),
                decision.fingerprint());
        return repository.populationEvidence(actor.tenantId(), scope)
                .map(evidence -> new ResolvedPopulation(workforce, scope, evidence));
    }

    public ResolvedPopulation requireTeam() {
        return findTeam().orElseThrow(() -> forbidden(
                "No current direct-report or approved delegated HCM population exists."));
    }

    public ResolvedPopulation requireTeamForMutation() {
        return findTeam(true).orElseThrow(() -> forbidden(
                "No locked direct-report or delegated HCM population permits this mutation."));
    }

    public ResolvedPopulation requireOperations(String action) {
        return findOperations(action).orElseThrow(() -> forbidden(
                "No current HCM target-population policy permits this operation."));
    }

    public ResolvedPopulation requireOperationsForMutation(String action) {
        return findOperations(action, true).orElseThrow(() -> forbidden(
                "No locked HCM target-population policy permits this mutation."));
    }

    /**
     * Recomputes the selected derived scope at the owner service. This catches
     * population or relationship changes even inside the gateway revalidation
     * window. Baseline traffic has no v3 evidence and retains its legacy path.
     */
    public void requireTrustedScope(
            ResolvedPopulation population,
            String surfaceKey,
            String kind,
            String... resolverSources) {
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        if (evidence == null) return;
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        for (String source : resolverSources) {
            String sourceKey = ProductSurfaceScopeKey.key(
                    actor.tenantId(), actor.userId(), "hcm", surfaceKey, source, kind);
            String derived = HcmEligibilityScopeKeys.derived(
                    actor.tenantId(), actor.userId(), surfaceKey, sourceKey,
                    population.relationshipRevision(), population.targetPopulationRevision());
            if (derived.equals(evidence.scopeKey())) return;
        }
        throw forbidden("The selected HCM population changed or is outside the current boundary.");
    }

    public void requireField(ResolvedPopulation population, String fieldGroup) {
        if (!population.scope().fieldGroups().contains(fieldGroup)) {
            throw forbidden("The current HCM boundary excludes the required field group.");
        }
    }

    public void requireSelfScope() {
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        if (evidence == null) return;
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPopulationRepository.ActorWorkforce workforce = repository.actor(
                actor.tenantId(), actor.personPublicId()).orElseThrow(() -> forbidden(
                        "The current identity is not linked to an active HCM worker."));
        String sourceKey = ProductSurfaceScopeKey.key(
                actor.tenantId(), actor.userId(), "hcm", "hcm.personal", "SELF", "SELF");
        String targetRevision = "self:" + workforce.personId() + ':' + workforce.revision();
        String derived = HcmEligibilityScopeKeys.derived(
                actor.tenantId(), actor.userId(), "hcm.personal", sourceKey,
                workforce.revision(), targetRevision);
        if (!derived.equals(evidence.scopeKey())) {
            throw forbidden("The selected HCM self scope changed or is no longer current.");
        }
    }

    public void requireConfigurationScope() {
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        if (evidence == null) return;
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        String revision = repository.tenantRevision(actor.tenantId()).orElseThrow(() ->
                forbidden("No active HCM configuration population exists for this tenant."));
        String sourceKey = ProductSurfaceScopeKey.key(
                actor.tenantId(), actor.userId(), "hcm", "hcm.management",
                "RS_HCM_CONFIG", "RESOURCE_SET");
        String derived = HcmEligibilityScopeKeys.derived(
                actor.tenantId(), actor.userId(), "hcm.management", sourceKey,
                "auth-responsibility", "config:" + revision);
        if (!derived.equals(evidence.scopeKey())) {
            throw forbidden("The selected HCM configuration scope changed or is invalid.");
        }
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    public record ResolvedPopulation(
            HcmPopulationRepository.ActorWorkforce actor,
            HcmPopulationRepository.PopulationScope scope,
            HcmPopulationRepository.PopulationEvidence evidence) {

        public String relationshipRevision() {
            return actor == null ? "operator" : actor.revision();
        }

        public String targetPopulationRevision() {
            return evidence.revision() + ':' + scope.policyFingerprint();
        }
    }
}
