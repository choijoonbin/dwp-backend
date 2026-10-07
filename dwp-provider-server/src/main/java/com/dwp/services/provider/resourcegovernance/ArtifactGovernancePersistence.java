package com.dwp.services.provider.resourcegovernance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.*;

/** Dependency-inverted persistence contract for artifact and rollout governance. */
interface ArtifactGovernancePersistence<A, R, P, E> {
    List<A> artifacts(int fetchLimit);
    Optional<A> artifact(UUID artifactId);
    Optional<A> lockArtifact(UUID artifactId);
    A createArtifact(UUID artifactId, CreateArtifactManifestRequest request, Long actorId);
    boolean assessCompatibility(UUID artifactId,
                                AssessArtifactCompatibilityRequest request, Long actorId);
    boolean submitArtifact(UUID artifactId, long version, Long actorId);
    boolean decideArtifact(UUID artifactId,
                           ArtifactReviewDecisionRequest request, Long actorId);
    List<R> reviews(UUID artifactId, int fetchLimit);
    List<P> plans(int fetchLimit);
    Optional<P> plan(UUID planId);
    Optional<P> lockPlan(UUID planId);
    P createPlan(UUID planId, CreateArtifactRolloutPlanRequest request, Long actorId);
    boolean submitPlan(UUID planId, long version);
    boolean decidePlan(UUID planId, long version, String decision,
                       String reason, Long actorId);
    boolean markPlanReady(UUID planId, long version);
    E appendEvidence(UUID evidenceId, UUID planId,
                     AppendArtifactEvidenceRequest request, Long actorId);
    List<E> evidence(UUID planId, int fetchLimit);
    List<E> readinessEvidence(UUID planId);
}
