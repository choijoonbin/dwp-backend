package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.auditrecords.ApprovalAuditModels.*;

@Service
public class ApprovalAuditService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_EXPORT_EVENTS = 5_000;
    private static final Duration MAX_SEARCH_WINDOW = Duration.ofDays(366);

    private final ApprovalAuditRepository repository;
    private final ApprovalAuditRedactor redactor;
    private final ApprovalAuditExternalAttestationVerifier attestationVerifier;
    private final ObjectMapper canonicalMapper;
    private final Clock clock;

    @Autowired
    public ApprovalAuditService(
            ApprovalAuditRepository repository,
            ObjectMapper mapper,
            ApprovalAuditExternalAttestationVerifier attestationVerifier) {
        this(repository, mapper, Clock.systemUTC(), attestationVerifier);
    }

    ApprovalAuditService(
            ApprovalAuditRepository repository,
            ObjectMapper mapper,
            Clock clock,
            ApprovalAuditExternalAttestationVerifier attestationVerifier) {
        this.repository = repository;
        this.redactor = new ApprovalAuditRedactor(mapper);
        this.attestationVerifier = attestationVerifier;
        this.canonicalMapper = canonicalMapper(mapper);
        this.clock = clock;
    }

    static ObjectMapper canonicalMapper(ObjectMapper source) {
        ObjectMapper canonical = source.copy();
        canonical.setConfig(canonical.getSerializationConfig()
                .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
        return canonical;
    }

    @Transactional(readOnly = true)
    public SearchPage search(Scope scope, SearchFilter requested, AccessLevel level) {
        scope.require(Capability.VIEW);
        requireLevel(scope, level);
        SearchFilter filter = validate(requested, true);
        List<EventProjection> events = repository.search(
                        scope, filter, level, filter.limit() + 1)
                .stream().map(raw -> project(raw, level)).toList();
        boolean hasMore = events.size() > filter.limit();
        List<EventProjection> page = hasMore ? events.subList(0, filter.limit()) : events;
        EventProjection last = page.isEmpty() ? null : page.getLast();
        Cursor next = hasMore ? new Cursor(last.occurredAt(), last.eventId()) : null;
        return new SearchPage(clock.instant(), level, List.copyOf(page), next);
    }

    @Transactional(readOnly = true)
    public EventProjection event(Scope scope, UUID eventId, AccessLevel level) {
        scope.require(Capability.VIEW);
        requireLevel(scope, level);
        if (eventId == null) {
            throw invalid("The audit event identity is required.");
        }
        ApprovalAuditRepository.RawEvent event = repository.eventById(scope, eventId);
        if (event == null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return project(event, level);
    }

    @Transactional(readOnly = true)
    public RequestGovernanceLinkage requestGovernanceLinkage(
            Scope scope,
            UUID requestId) {
        scope.require(Capability.VIEW);
        if (requestId == null) {
            throw invalid("The Approval request identity is required.");
        }
        RetentionLinkage retention = repository.retentionByRequest(scope, requestId);
        if (retention == null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return new RequestGovernanceLinkage(
                requestId, retention, "APPROVAL_DOCUMENT_OWNER",
                "/v1/admin/document-tools/holds/" + requestId, clock.instant());
    }

    @Transactional
    public SavedView createSavedView(
            Scope scope,
            UUID savedViewId,
            String name,
            Visibility visibility,
            SearchFilter requested) {
        if (visibility == Visibility.SHARED) {
            scope.require(Capability.MANAGE_SHARED_VIEWS);
        } else {
            scope.require(Capability.MANAGE_PERSONAL_VIEWS);
        }
        if (savedViewId == null || name == null || name.isBlank()
                || !name.equals(name.strip()) || name.length() > 120) {
            throw invalid("The saved-view identity and name are invalid.");
        }
        SearchFilter filter = validate(requested, false);
        SavedView existing = repository.savedView(scope, savedViewId);
        if (existing != null) {
            if (existing.ownerUserId() != scope.actorUserId()
                    || !existing.name().equals(name)
                    || existing.visibility() != visibility
                    || !existing.filter().equals(filter)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The saved-view identity is already bound to another command.");
            }
            return existing;
        }
        repository.insertSavedView(
                scope, savedViewId, name, visibility, filter, clock.instant());
        return repository.savedViews(scope).stream()
                .filter(view -> view.savedViewId().equals(savedViewId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<SavedView> savedViews(Scope scope) {
        scope.require(Capability.VIEW);
        return repository.savedViews(scope);
    }

    @Transactional(readOnly = true)
    public SavedView savedView(Scope scope, UUID savedViewId) {
        scope.require(Capability.VIEW);
        if (savedViewId == null) {
            throw invalid("The saved-view identity is required.");
        }
        SavedView view = repository.savedView(scope, savedViewId);
        if (view == null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return view;
    }

    @Transactional
    public ExportReceipt export(
            Scope scope,
            UUID exportId,
            SearchFilter requested,
            AccessLevel level) {
        scope.require(Capability.EXPORT);
        requireLevel(scope, level);
        if (exportId == null) {
            throw invalid("The export identity is required.");
        }
        ExportReceipt existing = repository.export(scope, exportId);
        if (existing != null) {
            SearchFilter filter = validate(requested, false);
            if (!repository.exportRequestMatches(scope, exportId, level, filter)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The export identity is already bound to another command.");
            }
            return existing;
        }
        SearchFilter filter = validate(requested, false);
        int count = repository.count(scope, filter, level);
        if (count > MAX_EXPORT_EVENTS) {
            throw invalid("The export exceeds the governed 5000-event limit.");
        }
        List<EventProjection> events = repository.search(
                        scope, filter, level, MAX_EXPORT_EVENTS)
                .stream().map(raw -> project(raw, level)).toList();
        Map<String, Integer> retention = retentionSummary(events);
        List<ExportEntry> entries = events.stream()
                .map(event -> new ExportEntry(
                        event.eventId(), event.requestId(),
                        redactor.evidenceSha256(event), event.retention()))
                .toList();
        ExportManifest manifest = new ExportManifest(
                exportId, clock.instant(), scope.resourceSetKey(), level,
                entries.size(), entries, retention, List.of(
                        "Manifest digest verifies exported bytes only.",
                        "No WORM, KMS, or external archive assurance is claimed without a linked attestation.",
                        "Legal-hold and retention values are point-in-time owner-record snapshots."));
        String hash = sha256(manifest);
        repository.insertCompletedExport(
                scope, level, filter, manifest, hash, retention, clock.instant());
        return repository.export(scope, exportId);
    }

    @Transactional
    public ExportReceipt linkVerifiedExternalAttestation(
            Scope scope,
            UUID exportId,
            long expectedVersion,
            ExternalAttestationSubmission submitted) {
        scope.require(Capability.LINK_EXTERNAL_ATTESTATION);
        ExportReceipt current = repository.export(scope, exportId);
        if (current == null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        requireVerifiedDigest(current);
        VerifiedExternalAttestation attestation =
                attestationVerifier.verify(scope, current, submitted);
        if (!repository.linkAttestation(scope, exportId, expectedVersion, attestation)) {
            ExportReceipt existing = repository.export(scope, exportId);
            if (existing != null
                    && existing.version() == expectedVersion + 1
                    && attestation.type().equals(existing.externalAttestationType())
                    && attestation.reference().equals(existing.externalAttestationReference())
                    && attestation.attestedAt().equals(existing.externalAttestedAt())
                    && attestation.issuer().equals(existing.externalAttestationIssuer())
                    && attestation.attestorIdentity().equals(
                            existing.externalAttestorIdentity())
                    && attestation.keyId().equals(existing.externalAttestationKeyId())
                    && attestation.verificationReference().equals(
                            existing.externalVerificationReference())) {
                return existing;
            }
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The export or its integrity state changed.");
        }
        return repository.export(scope, exportId);
    }

    @Transactional
    public VerificationReceipt verifyExport(
            Scope scope,
            UUID exportId,
            VerificationCommand command) {
        scope.require(Capability.VERIFY_EXPORT);
        if (exportId == null || command == null || command.verificationId() == null
                || command.expectedExportVersion() < 0) {
            throw invalid("The export verification command is invalid.");
        }
        ExportReceipt current = repository.export(scope, exportId);
        if (current == null || current.manifest() == null
                || current.manifestSha256() == null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        requireLevel(scope, current.manifest().accessLevel());
        String recomputed = sha256(current.manifest());
        VerificationReceipt receipt = repository.insertVerification(
                scope, exportId, command, current.manifestSha256(),
                recomputed, clock.instant());
        if (receipt == null) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The export changed, the verifier is not independent, or the verification identity is already used.");
        }
        return receipt;
    }

    @Transactional(readOnly = true)
    public List<VerificationReceipt> verifications(Scope scope, UUID exportId) {
        exportReceipt(scope, exportId);
        return repository.verifications(scope, exportId);
    }

    @Transactional(readOnly = true)
    public ExportReceipt exportReceipt(Scope scope, UUID exportId) {
        scope.require(Capability.VIEW);
        if (exportId == null) {
            throw invalid("The export identity is required.");
        }
        ExportReceipt receipt = repository.export(scope, exportId);
        if (receipt == null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        if (receipt.manifest() != null) {
            requireLevel(scope, receipt.manifest().accessLevel());
            requireVerifiedDigest(receipt);
        }
        return receipt;
    }

    private void requireVerifiedDigest(ExportReceipt receipt) {
        if (receipt.manifest() == null || receipt.manifestSha256() == null
                || !receipt.manifestSha256().equals(sha256(receipt.manifest()))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Stored audit export bytes do not match the sealed manifest digest.");
        }
    }

    private EventProjection project(
            ApprovalAuditRepository.RawEvent raw,
            AccessLevel level) {
        String actorIdentifier = redactor.actorIdentifier(raw.actorId(), level);
        return new EventProjection(
                raw.eventId(), raw.requestId(), raw.requestNumber(),
                raw.eventType(), raw.outcome(),
                new ActorProjection(raw.actorType(), actorIdentifier,
                        level == AccessLevel.PRIVILEGED && actorIdentifier != null),
                level == AccessLevel.METADATA ? null : raw.message(),
                redactor.redact(raw.evidence(), level),
                raw.retention(), raw.occurredAt());
    }

    private SearchFilter validate(SearchFilter filter, boolean pageLimit) {
        if (filter == null || filter.from() == null || filter.to() == null
                || !filter.to().isAfter(filter.from())
                || Duration.between(filter.from(), filter.to()).compareTo(MAX_SEARCH_WINDOW) > 0
                || filter.to().isAfter(clock.instant().plusSeconds(60))) {
            throw invalid("The audit search window is invalid.");
        }
        int limit = pageLimit ? filter.limit() : MAX_EXPORT_EVENTS;
        if (pageLimit && (limit < 1 || limit > MAX_PAGE_SIZE)) {
            throw invalid("The audit page size must be between 1 and 200.");
        }
        Set<String> outcomes = filter.outcomes();
        if (!Set.of("SUCCESS", "DENIED", "FAILED").containsAll(outcomes)
                || filter.eventTypes().stream().anyMatch(type ->
                        type == null || !type.matches("[A-Z][A-Z0-9_]{1,79}"))
                || filter.text() != null && filter.text().length() > 160) {
            throw invalid("The audit filter is invalid.");
        }
        return new SearchFilter(
                filter.from(), filter.to(), filter.eventTypes(), outcomes,
                filter.requestId(), filter.text(), limit, filter.cursor());
    }

    private void requireLevel(Scope scope, AccessLevel level) {
        if (level == null) {
            throw invalid("The audit access level is required.");
        }
        if (level == AccessLevel.PRIVILEGED) {
            scope.require(Capability.VIEW_PRIVILEGED);
        }
        if (level == AccessLevel.AUDITOR) {
            scope.require(Capability.VIEW_AUDITOR);
        }
    }

    private Map<String, Integer> retentionSummary(List<EventProjection> events) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        events.forEach(event -> summary.merge(event.retention().status(), 1, Integer::sum));
        return Map.copyOf(summary);
    }

    private String sha256(Object value) {
        try {
            return ApprovalAuditRedactor.sha256(canonicalMapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The export manifest cannot be canonicalized.", exception);
        }
    }

    static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
