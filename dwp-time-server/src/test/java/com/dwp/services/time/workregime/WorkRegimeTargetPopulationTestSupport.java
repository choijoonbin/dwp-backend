package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetBindingEvidence;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.PopulationAccess;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.TargetMembershipEvidence;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class WorkRegimeTargetPopulationTestSupport {

    static final String GATEWAY_SCOPE_A = "scope-" + "a".repeat(32);
    static final String GATEWAY_SCOPE_B = "scope-" + "b".repeat(32);
    static final String GATEWAY_SCOPE_C = "scope-" + "c".repeat(32);
    static final String DIGEST_A = "a".repeat(64);
    static final String DIGEST_B = "b".repeat(64);
    static final String DIGEST_C = "c".repeat(64);

    private WorkRegimeTargetPopulationTestSupport() {
    }

    static WorkRegimeTargetPopulationResolver resolver(
            long tenantId,
            Map<String, UUID> populationsByGatewayScope,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long assignmentRevision,
            EffectivePeriod period,
            Instant now) {
        return new WorkRegimeTargetPopulationResolver() {
            @Override
            public Optional<PopulationAccess> resolveActorAccess(
                    long requestedTenant,
                    long actorId,
                    String gatewayScopeKey,
                    Instant checkedAt) {
                UUID population = populationsByGatewayScope.get(gatewayScopeKey);
                if (requestedTenant != tenantId || population == null) return Optional.empty();
                return Optional.of(new PopulationAccess(
                        tenantId,
                        actorId,
                        gatewayScopeKey,
                        population,
                        WorkRegimeTargetPopulationResolver.stableScopeRef(population),
                        1L,
                        1L,
                        DIGEST_A,
                        DIGEST_B,
                        now.plusSeconds(3_600)));
            }

            @Override
            public Optional<TargetMembershipEvidence> resolveTargetMembership(
                    long requestedTenant,
                    UUID populationPublicId,
                    UUID requestedWorker,
                    UUID requestedAssignment,
                    long requestedRevision,
                    EffectivePeriod requiredPeriod,
                    Instant checkedAt) {
                boolean exact = requestedTenant == tenantId
                        && populationsByGatewayScope.containsValue(populationPublicId)
                        && workerPublicId.equals(requestedWorker)
                        && peopleAssignmentPublicId.equals(requestedAssignment)
                        && assignmentRevision == requestedRevision
                        && period.contains(requiredPeriod);
                return exact ? Optional.of(new TargetMembershipEvidence(
                        tenantId,
                        populationPublicId,
                        1L,
                        workerPublicId,
                        peopleAssignmentPublicId,
                        assignmentRevision,
                        1L,
                        period,
                        DIGEST_A,
                        DIGEST_C)) : Optional.empty();
            }
        };
    }

    static TargetBindingEvidence evidence(
            UUID populationPublicId,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long assignmentRevision,
            long actorId,
            String gatewayScope,
            Instant verifiedAt) {
        return new TargetBindingEvidence(
                populationPublicId,
                1L,
                workerPublicId,
                peopleAssignmentPublicId,
                assignmentRevision,
                1L,
                actorId,
                gatewayScope,
                1L,
                DIGEST_A,
                DIGEST_C,
                DIGEST_B,
                verifiedAt);
    }

    static WorkRegimeTargetPopulationResolver resolverAnyMember(
            long tenantId,
            String gatewayScope,
            UUID populationPublicId,
            EffectivePeriod period,
            Instant now) {
        return new WorkRegimeTargetPopulationResolver() {
            @Override
            public Optional<PopulationAccess> resolveActorAccess(
                    long requestedTenant,
                    long actorId,
                    String gatewayScopeKey,
                    Instant checkedAt) {
                if (requestedTenant != tenantId || !gatewayScope.equals(gatewayScopeKey)) {
                    return Optional.empty();
                }
                return Optional.of(new PopulationAccess(
                        tenantId, actorId, gatewayScope, populationPublicId,
                        WorkRegimeTargetPopulationResolver.stableScopeRef(
                                populationPublicId), 1L, 1L,
                        DIGEST_A, DIGEST_B, now.plusSeconds(3_600)));
            }

            @Override
            public Optional<TargetMembershipEvidence> resolveTargetMembership(
                    long requestedTenant,
                    UUID requestedPopulation,
                    UUID workerPublicId,
                    UUID peopleAssignmentPublicId,
                    long peopleAssignmentRevision,
                    EffectivePeriod requiredPeriod,
                    Instant checkedAt) {
                if (requestedTenant != tenantId
                        || !populationPublicId.equals(requestedPopulation)
                        || !period.contains(requiredPeriod)) {
                    return Optional.empty();
                }
                return Optional.of(new TargetMembershipEvidence(
                        tenantId, populationPublicId, 1L, workerPublicId,
                        peopleAssignmentPublicId, peopleAssignmentRevision, 1L,
                        period, DIGEST_A, DIGEST_C));
            }
        };
    }
}
