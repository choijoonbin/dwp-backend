package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ApprovalFormUserDirectoryDtos.*;
import com.dwp.services.auth.repository.ApprovalFormUserDirectoryRepository;
import com.dwp.services.auth.service.ApprovalFormUserSourceProofVerifier.VerifiedSourceProof;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalFormUserDirectoryService {
    private final ObjectMapper mapper;
    private final ApprovalFormUserSourceProofVerifier verifier;
    private final ApprovalFormReferenceProofVerifier references;
    private final ApprovalFormUserProofReplayStore replay;
    private final ObjectProvider<ApprovalFormUserAuthorityPort> authorities;
    private final ApprovalFormUserDirectoryRepository repository;

    @Autowired
    public ApprovalFormUserDirectoryService(ObjectMapper mapper, ApprovalFormUserSourceProofVerifier verifier,
            ApprovalFormReferenceProofVerifier references, ApprovalFormUserProofReplayStore replay,
            ObjectProvider<ApprovalFormUserAuthorityPort> authorities, ApprovalFormUserDirectoryRepository repository) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.verifier = verifier;
        this.references = references;
        this.replay = replay;
        this.authorities = authorities;
        this.repository = repository;
    }

    public ApprovalFormUserDirectoryService(ObjectMapper mapper, ApprovalFormUserSourceProofVerifier verifier,
            ApprovalFormUserProofReplayStore replay, ObjectProvider<ApprovalFormUserAuthorityPort> authorities,
            ApprovalFormUserDirectoryRepository repository) {
        this(mapper, verifier, new ApprovalFormReferenceProofVerifier(verifier), replay, authorities, repository);
    }

    @Transactional(readOnly = true)
    public Response search(String body) {
        SearchRequest request = parse(body, SearchRequest.class);
        String digest = verifier.requestDigest("SEARCH", Map.of("query", request.query(), "size", request.size()));
        VerifiedSourceProof proof = verifier.verify(request.sourceProof(), "SEARCH", digest);
        ApprovalFormUserAuthorityPort authority = requireAuthority(proof);
        var current = checked(authority, proof);
        replay.consume(proof);
        List<ResolvedPerson> people = repository.search(proof.tenantId(), request.query(), request.size());
        if (people == null || people.size() > request.size()) throw denied();
        validatePeople(proof, people, null);
        unchanged(current, checked(authority, proof));
        return new Response(people, proof.proofId(), digest, current.authRevision(), current.policyRevision());
    }

    @Transactional(readOnly = true)
    public Response resolve(String body) {
        ResolveRequest request = parse(body, ResolveRequest.class);
        String digest = verifier.requestDigest("RESOLVE", Map.of("personPublicIds", request.personPublicIds()));
        VerifiedSourceProof proof = verifier.referenceProfile(request.sourceProof())
                ? references.verify(request.sourceProof(), digest) : verifier.verify(request.sourceProof(), "RESOLVE", digest);
        ApprovalFormUserAuthorityPort authority = requireAuthority(proof);
        var current = checked(authority, proof);
        replay.consume(proof);
        List<ResolvedPerson> people = repository.resolve(proof.tenantId(), request.personPublicIds());
        validatePeople(proof, people, Set.copyOf(request.personPublicIds()));
        unchanged(current, checked(authority, proof));
        return new Response(people, proof.proofId(), digest, current.authRevision(), current.policyRevision());
    }

    private ApprovalFormUserAuthorityPort requireAuthority(VerifiedSourceProof proof) {
        var ports = authorities.orderedStream().toList();
        if (ports.size() != 1) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Exact approval person source authority is unavailable.");
        return ports.getFirst();
    }

    private ApprovalFormUserAuthorityPort.CurrentAuthority checked(ApprovalFormUserAuthorityPort port,
            VerifiedSourceProof proof) {
        var current = port.requireCurrent(proof);
        if (current == null || current.authRevision() == null || current.authRevision().isBlank()
                || current.policyRevision() == null || current.policyRevision().isBlank()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exact approval person source authority is unavailable.");
        }
        return current;
    }

    private void unchanged(ApprovalFormUserAuthorityPort.CurrentAuthority before,
            ApprovalFormUserAuthorityPort.CurrentAuthority after) {
        if (!before.equals(after)) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                "Approval person source authority changed during resolution.");
    }

    private void validatePeople(VerifiedSourceProof proof, List<ResolvedPerson> people, Set<UUID> expected) {
        if (people == null) throw denied();
        var ids = new HashSet<UUID>();
        var subjects = new HashSet<Long>();
        for (var person : people) {
            if (person == null || person.tenantId() == null || person.tenantId() != proof.tenantId()
                    || person.subjectId() == null || person.subjectId() <= 0 || !subjects.add(person.subjectId())
                    || person.personPublicId() == null || !ids.add(person.personPublicId())
                    || !"TENANT".equals(person.identityPlane()) || !"ACTIVE".equals(person.status())
                    || person.displayName() == null || person.displayName().isBlank()
                    || person.displayName().length() > 200) throw denied();
        }
        if (expected != null && !expected.equals(ids)) throw denied();
    }

    private <T> T parse(String body, Class<T> type) {
        if (body == null || body.length() > 20000) throw invalid();
        try {
            var tree = mapper.readTree(body);
            if (tree == null || !tree.isObject()) throw invalid();
            if (type == ResolveRequest.class && tree.path("personPublicIds").isArray()) {
                for (var id : tree.path("personPublicIds")) {
                    if (!id.isTextual() || !UUID.fromString(id.textValue()).toString().equals(id.textValue())) throw invalid();
                }
            }
            return mapper.treeToValue(tree, type);
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "An exact bounded approval person lookup is required.");
    }

    private BaseException denied() {
        return new BaseException(ErrorCode.FORBIDDEN, "Selected people are unavailable in the current approval form source.");
    }
}
