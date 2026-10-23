package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owner-internal records. Digests and cryptographic metadata never cross the controller. */
final class Per008Domain {
    private Per008Domain() { }

    enum Operation {
        TEAM_QUERY,
        REQUEST_OPTIONS,
        REQUEST_CREATE,
        ASSIGNMENTS_MINE,
        ASSIGNMENT_DETAIL,
        RESPONSE_SUBMIT,
        RELEASE_READ
    }

    enum PepLayer {
        GATEWAY_APP_ENTITLEMENT,
        OWNER_API_OPERATION,
        RESOURCE_POPULATION,
        REPOSITORY_TENANT_PREDICATE,
        FIELD_PROJECTION,
        PURPOSE_POLICY,
        AUDIT_DECISION
    }

    record AuthorityRequest(Operation operation, UUID resourcePublicId) { }

    record SubjectBinding(UUID workerPublicId, UUID assignmentPublicId) {
        SubjectBinding {
            if (workerPublicId == null || assignmentPublicId == null) {
                throw Per008Problem.unavailable("PER008_SUBJECT_BINDING_UNAVAILABLE");
            }
        }
    }

    record AuthorityEvidence(
            long tenantId,
            long actorId,
            UUID authorizationPrincipalPublicId,
            UUID actorWorkerPublicId,
            UUID actorAssignmentPublicId,
            String routeContractKey,
            String profileKey,
            String capabilityKey,
            String purposeCode,
            String scopeDecisionRevision,
            String authorizationDecisionRevision,
            String fieldPolicyRevision,
            String populationScopeDigest,
            Map<String, FieldDecision> fieldDecisions,
            Set<SubjectBinding> permittedSubjectBindings,
            Set<PepLayer> satisfiedLayers) {
        AuthorityEvidence {
            fieldDecisions = Map.copyOf(fieldDecisions);
            permittedSubjectBindings = Set.copyOf(permittedSubjectBindings);
            satisfiedLayers = Set.copyOf(satisfiedLayers);
        }
        boolean selfProfile() {
            return Set.of("self-feedback-requester", "self-feedback-release-reader")
                    .contains(profileKey);
        }
        boolean permits(UUID worker, UUID assignment) {
            return selfProfile()
                    ? worker.equals(actorWorkerPublicId) && assignment.equals(actorAssignmentPublicId)
                    : permittedSubjectBindings.contains(new SubjectBinding(worker, assignment));
        }
        void requireAllLayers() {
            if (!satisfiedLayers.equals(Set.of(PepLayer.values()))) {
                throw Per008Problem.forbidden("PER008_PEP_INCOMPLETE");
            }
        }
    }

    record WorkforceEvidence(
            UUID projectionHeadId,
            long localRevision,
            long ownerRevision,
            OffsetDateTime snapshotAsOf,
            String ownerPageDigest,
            String contentHash,
            UUID workerPublicId,
            UUID assignmentPublicId,
            UUID managerAssignmentPublicId,
            String workforceStatus) { }

    record TemplateAuthority(
            UUID artifactPublicId,
            UUID templateVersionId,
            UUID configurationId,
            long platformVersion,
            String schemaKey,
            int schemaVersion,
            String payloadDigest,
            String scopeDigest,
            String title,
            List<Question> questions,
            byte[] encryptedContent,
            UUID encryptionKeyVersionPublicId) {
        TemplateAuthority {
            questions = List.copyOf(questions);
            encryptedContent = encryptedContent.clone();
        }
        @Override public byte[] encryptedContent() { return encryptedContent.clone(); }
    }

    record AudienceMember(
            UUID workerPublicId,
            UUID assignmentPublicId,
            RelationshipType relationshipType,
            UUID projectionHeadId,
            long localRevision,
            String maskedLabel) { }

    record PrivacyPolicy(
            UUID publicId,
            String revision,
            String digest,
            int minimumReleaseCount) { }

    /** Frozen owner policy; provider identities still come only from the exact V16 graph. */
    record AudiencePolicy(
            UUID publicId,
            String revision,
            String digest,
            Set<RelationshipType> relationshipTypes,
            Set<SubjectBinding> refereeBindings) {
        AudiencePolicy {
            relationshipTypes = Set.copyOf(relationshipTypes);
            refereeBindings = Set.copyOf(refereeBindings);
        }
    }

    record CreateResolution(
            WorkforceEvidence subject,
            TemplateAuthority template,
            PrivacyPolicy privacyPolicy,
            AudiencePolicy audiencePolicy,
            List<AudienceMember> audience,
            String audienceScopeDigest,
            String executionBindingDigest) {
        CreateResolution { audience = List.copyOf(audience); }
    }

    record RequestRecord(
            long tenantId,
            UUID publicId,
            long aggregateVersion,
            UUID cycleVersionId,
            UUID subjectWorkerPublicId,
            UUID subjectAssignmentPublicId,
            UUID authorizationPrincipalPublicId,
            UUID templateArtifactPublicId,
            String templateContentHash,
            String requestTitle,
            RequestState state,
            AnonymityMode anonymityMode,
            int effectiveMinimumReleaseCount,
            UUID privacyPolicyPublicId,
            String thresholdPolicyRevision,
            String thresholdPolicyDigest,
            OffsetDateTime opensAt,
            OffsetDateTime closesAt,
            OffsetDateTime createdAt,
            OffsetDateTime aggregatedAt,
            OffsetDateTime releasedAt) { }

    record AssignmentRecord(
            long tenantId,
            UUID publicId,
            UUID requestPublicId,
            UUID providerWorkerPublicId,
            UUID providerAssignmentPublicId,
            RelationshipType relationshipType,
            AssignmentState state,
            OffsetDateTime submittedAt) { }

    record AssignmentDetailRecord(
            RequestRecord request,
            AssignmentRecord assignment,
            TemplateAuthority template) { }

    record ProtectedContent(
            byte[] encodedEnvelope,
            byte[] nonce,
            byte[] authenticationTag,
            UUID encryptionKeyVersionPublicId,
            String canonicalContentHmac,
            UUID digestKeyVersionPublicId) {
        ProtectedContent {
            encodedEnvelope = encodedEnvelope.clone();
            nonce = nonce.clone();
            authenticationTag = authenticationTag.clone();
        }
        @Override public byte[] encodedEnvelope() { return encodedEnvelope.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] authenticationTag() { return authenticationTag.clone(); }
    }

    record ReceiptRecord(
            UUID receiptId,
            String clientIntentDigest,
            UUID aggregateId,
            UUID resultRef,
            long appliedVersion,
            OffsetDateTime completedAt,
            String state) { }

    record ReleaseSnapshot(
            UUID publicId,
            UUID requestPublicId,
            ProtectedContent content,
            String sourceSetDigest,
            String aggregationAlgorithmVersion,
            OffsetDateTime aggregatedAt) { }

    record LifecycleCommand(
            String authorityKey,
            long tenantId,
            UUID requestId,
            long expectedVersion,
            String idempotencyKey) { }

    record LifecycleReceipt(
            UUID receiptId,
            UUID requestId,
            RequestState state,
            long version,
            OffsetDateTime occurredAt,
            boolean replayed) { }
}
