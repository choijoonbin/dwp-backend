package com.dwp.services.approval.document;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;

@Service
public class ApprovalDocumentService {
    private final ApprovalDocumentAuthority authority;
    private final ApprovalDocumentOwnerRepository owners;
    private final ApprovalDocumentRepository repository;
    private final ApprovalDocumentPolicy validator;
    private final ApprovalDocumentRenderer renderer;
    private final ApprovalDocumentAudit audit;
    public ApprovalDocumentService(ApprovalDocumentAuthority authority, ApprovalDocumentOwnerRepository owners,
                                   ApprovalDocumentRepository repository, ApprovalDocumentPolicy validator,
                                   ApprovalDocumentRenderer renderer, ApprovalDocumentAudit audit) {
        this.authority = authority; this.owners = owners; this.repository = repository; this.validator = validator;
        this.renderer = renderer; this.audit = audit;
    }

    @Transactional
    public Tools tools(OwnerType type, UUID id) {
        var actor = authority.actor(resource(type) + ":VIEW");
        var owner = owners.lock(actor, type, id); authority.require(owner, "VIEW");
        var policy = repository.policy(actor, owner.resourceSetKey(), false); validator.validate(policy.published().rules());
        var head = repository.head(actor, owner.requestId(), policy.published().rules().evidenceRetentionDays());
        var rules = policy.published().rules();
        boolean update = actor.permissions().contains(owner.resource() + ":UPDATE");
        boolean export = actor.permissions().contains(owner.resource() + ":EXPORT");
        if (update) { try { authority.require(owner, "UPDATE"); } catch (com.dwp.core.exception.BaseException e) { update = false; } }
        if (export) { try { authority.require(owner, "EXPORT"); } catch (com.dwp.core.exception.BaseException e) { export = false; } }
        boolean classified = rules.allowedClassifications().contains(owner.classification());
        boolean retained = head.effectiveHold() || head.retainUntil().isAfter(Instant.now());
        authority.require(owner, "VIEW");
        return new Tools(owner.requestId(), owner.taskId(), owner.requestVersion(), owner.taskVersion(), owner.payloadRevision(),
                owner.payloadSha256(), policy.version(), head.commentsVersion(), head.holdVersion(), head.activeHold(),
                tool(true, "ALLOWED"), tool(true, "ALLOWED"), tool(update && retained && rules.allowComments(), "COMMENT_PROHIBITED"),
                tool(export && retained && classified && rules.allowPrint(), "PRINT_PROHIBITED"),
                tool(export && retained && classified && rules.allowJsonExport(), "EXPORT_PROHIBITED"),
                tool(false, "ATTACHMENT_STORAGE_NOT_CONFIGURED"), Instant.now(), policy.policyId(), policy.resourceSetKey(),
                tool(owner.taskId() == null && completed(owner.status()) && export && retained && classified
                        && rules.allowJsonExport() && rules.allowArchiveExport(), "ARCHIVE_EXPORT_PROHIBITED"), rules.maxBatchItems(), head.effectiveHold() && !head.activeHold());
    }

    @Transactional
    public Comments comments(OwnerType type, UUID id, int page, int size) {
        page(page, size);
        var actor = authority.actor(resource(type) + ":VIEW");
        var owner = owners.lock(actor, type, id); authority.require(owner, "VIEW");
        var policy = repository.policy(actor, owner.resourceSetKey(), false);
        var head = repository.head(actor, owner.requestId(), policy.published().rules().evidenceRetentionDays());
        var result = repository.comments(actor, owner.requestId(), head, page, size);
        authority.require(owner, "VIEW"); return result;
    }

    @Transactional
    public Comment append(OwnerType type, UUID id, AppendComment input) {
        var actor = authority.actor(resource(type) + ":UPDATE");
        String route = "/v1/" + (type == OwnerType.REQUEST ? "requests/" : "tasks/") + id + "/comments";
        var prior = repository.receipt(actor, route, input.idempotencyKey(), input);
        var owner = owners.lock(actor, type, id); authority.require(owner, "UPDATE");
        var policy = repository.policy(actor, owner.resourceSetKey(), false);
        if (!policy.published().rules().allowComments()) throw ApprovalDocumentCanonical.forbidden();
        var head = repository.head(actor, owner.requestId(), policy.published().rules().evidenceRetentionDays());
        retained(head);
        if (prior != null) {
            var result = repository.comment(actor, owner.requestId(), UUID.fromString(prior.get("commentId").toString()), head.effectiveHold());
            authority.require(owner, "UPDATE"); return result;
        }
        if (input.expectedVersion() == null || owner.version() != input.expectedVersion()
                || input.expectedCommentsVersion() == null || head.commentsVersion() != input.expectedCommentsVersion()) throw ApprovalDocumentCanonical.conflict();
        if (input.text() == null || input.text().isBlank() || input.text().length() > 2000 || input.text().indexOf('\u0000') >= 0) throw ApprovalDocumentCanonical.forbidden();
        var result = repository.append(actor, owner, head, input.text().trim(), policy.published().rules().evidenceRetentionDays());
        authority.require(owner, "UPDATE");
        var metadata = Map.<String, Object>of("commentId", result.commentId(), "requestId", owner.requestId(), "sequence", result.sequence());
        repository.complete(actor, route, input.idempotencyKey(), input, metadata, policy.published().rules().evidenceRetentionDays());
        audit.record(actor, owner.requestId(), "APPROVAL_DOCUMENT_COMMENT_APPENDED", input.idempotencyKey(), metadata);
        return result;
    }

    @Transactional
    public GeneratedDocument export(OwnerType type, UUID id, Export input) {
        var actor = authority.actor(resource(type) + ":EXPORT");
        String route = "/v1/" + (type == OwnerType.REQUEST ? "requests/" : "tasks/") + id + "/document-exports";
        var prior = repository.receipt(actor, route, input.idempotencyKey(), input);
        var owner = owners.lock(actor, type, id); authority.require(owner, "EXPORT");
        var policy = repository.policy(actor, owner.resourceSetKey(), false);
        requireExport(policy, owner, input.expectedPolicyVersion(), input.intent(), false);
        version(owner, input.expectedVersion(), input.payloadRevision());
        var doc = document(owner, policy);
        var result = generated(List.of(doc), input.intent(), policy, prior);
        authority.require(owner, "EXPORT");
        if (prior == null) finish(actor, route, input.idempotencyKey(), input, result, owner.requestId(), policy);
        return result;
    }

    @Transactional
    public GeneratedDocument archive(ArchiveExport input) {
        var actor = authority.actor("ACTION.APPROVAL_REQUEST:EXPORT");
        String route = "/v1/requests/archive/document-exports";
        var prior = repository.receipt(actor, route, input.idempotencyKey(), input);
        if (input.items() == null || input.items().isEmpty() || input.items().size() > 50
                || input.items().stream().map(ArchiveItem::requestId).distinct().count() != input.items().size()) throw ApprovalDocumentCanonical.conflict();
        var documents = new ArrayList<Document>(); var locked = new ArrayList<ApprovalDocumentOwnerRepository.Owner>();
        Policy policy = null;
        for (var item : input.items().stream().sorted(Comparator.comparing(ArchiveItem::requestId)).toList()) {
            var owner = owners.lock(actor, OwnerType.REQUEST, item.requestId()); authority.require(owner, "EXPORT");
            if (!completed(owner.status())) throw ApprovalDocumentCanonical.forbidden();
            var next = repository.policy(actor, owner.resourceSetKey(), false);
            if (!next.policyId().equals(input.expectedPolicyId()) || !next.resourceSetKey().equals(input.resourceSetKey())) throw ApprovalDocumentCanonical.conflict();
            if (policy != null && !policy.policyId().equals(next.policyId())) throw ApprovalDocumentCanonical.forbidden();
            policy = next; requireExport(policy, owner, input.expectedPolicyVersion(), Intent.DOWNLOAD, true);
            if (input.items().size() > policy.published().rules().maxBatchItems()) throw ApprovalDocumentCanonical.conflict();
            version(owner, item.expectedVersion(), item.payloadRevision()); locked.add(owner); documents.add(document(owner, policy));
        }
        var result = generated(documents, Intent.DOWNLOAD, policy, prior);
        locked.forEach(owner -> authority.require(owner, "EXPORT"));
        if (prior == null) finish(actor, route, input.idempotencyKey(), input, result, locked.getFirst().requestId(), policy);
        return result;
    }

    private Document document(ApprovalDocumentOwnerRepository.Owner owner, Policy policy) {
        var actor = authority.actor(owner.resource() + ":EXPORT"); var rules = policy.published().rules(); validator.validate(rules);
        owners.requireImmutablePayload(actor, owner);
        var head = repository.head(actor, owner.requestId(), rules.evidenceRetentionDays());
        retained(head);
        var comments = rules.includeComments() ? repository.comments(actor, owner.requestId(), head, 0, 100) : null;
        if (comments != null && comments.totalElements() > 100) throw ApprovalDocumentCanonical.conflict();
        var evidence = rules.includeEvidence() ? repository.evidence(actor, owner.requestId()) : List.<Evidence>of();
        if (evidence.size() > 500) throw ApprovalDocumentCanonical.conflict();
        return new Document(owner.requestId(), owner.taskId(), owner.requestNumber(), owner.title(), owner.summary(),
                owner.status(), owner.classification(), owner.requestVersion(), owner.taskVersion(), owner.payloadRevision(),
                owner.payloadSha256(), renderer.fields(owner.payload(), owner.formSchema(), rules.fields()), comments == null ? List.of() : comments.items(), evidence,
                head.activeHold(), head.retainUntil(), head.effectiveHold() && !head.activeHold());
    }

    private GeneratedDocument generated(List<Document> docs, Intent intent, Policy policy, Map<String, Object> prior) {
        Instant now = Instant.now();
        Instant generated = prior == null ? now : Instant.parse(prior.get("generatedAt").toString());
        Instant expires = prior == null ? generated.plusSeconds(policy.published().rules().snapshotTtlSeconds()) : Instant.parse(prior.get("expiresAt").toString());
        if (!expires.isAfter(now)) throw ApprovalDocumentCanonical.conflict();
        Instant retain = prior == null ? generated.plusSeconds(policy.published().rules().evidenceRetentionDays() * 86400L)
                : Instant.parse(prior.get("retainUntil").toString());
        Instant sourceRetain = docs.stream().filter(d -> !d.legalHold() && !d.preservationPending()).map(Document::retainUntil).min(Instant::compareTo).orElse(retain);
        if (sourceRetain.isBefore(retain)) retain = sourceRetain;
        String content = renderer.render(docs, intent, generated); String sha = ApprovalDocumentCanonical.sha(content);
        long bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > policy.published().rules().maxBytes()) throw ApprovalDocumentCanonical.conflict();
        if (prior != null && !sha.equals(prior.get("sha256"))) throw ApprovalDocumentCanonical.conflict();
        UUID id = prior == null ? UUID.randomUUID() : UUID.fromString(prior.get("exportId").toString());
        boolean print = intent == Intent.PRINT;
        return new GeneratedDocument(id, print ? "HTML" : "JSON", print ? "text/html" : "application/json",
                "approval-" + id + (print ? ".html" : ".json"), sha, bytes, policy.version(), generated, expires, retain, content);
    }
    private void finish(com.dwp.services.approval.security.ApprovalRequestContext.Actor actor, String route, String key, Object input,
                        GeneratedDocument result, UUID request, Policy policy) {
        var metadata = new java.util.LinkedHashMap<>(Map.<String, Object>of("exportId", result.exportId(), "generatedAt", result.generatedAt(), "expiresAt", result.expiresAt(),
                "retainUntil", result.retainUntil(), "sha256", result.sha256(), "policyVersion", result.policyVersion(), "format", result.format(),
                "requestId", request, "sizeBytes", result.sizeBytes()));
        metadata.put("sourcePath", route);
        metadata.put("requestedVersions", input instanceof Export export
                ? Map.of("ownerVersion", export.expectedVersion(), "payloadRevision", export.payloadRevision())
                : ((ArchiveExport) input).items());
        repository.complete(actor, route, key, input, metadata, policy.published().rules().evidenceRetentionDays());
        audit.record(actor, request, "APPROVAL_DOCUMENT_EXPORTED", key, metadata);
    }
    private void requireExport(Policy policy, ApprovalDocumentOwnerRepository.Owner owner, Long expectedPolicy, Intent intent, boolean archive) {
        var rules = policy.published().rules(); validator.validate(rules);
        if (expectedPolicy == null || expectedPolicy != policy.version()) throw ApprovalDocumentCanonical.conflict();
        if (!rules.allowedClassifications().contains(owner.classification()) || intent == null
                || (intent == Intent.PRINT ? !rules.allowPrint() : !rules.allowJsonExport())
                || (archive && !rules.allowArchiveExport())) throw ApprovalDocumentCanonical.forbidden();
    }
    private void version(ApprovalDocumentOwnerRepository.Owner owner, Long version, Integer revision) {
        if (version == null || version != owner.version() || revision == null || revision != owner.payloadRevision()) throw ApprovalDocumentCanonical.conflict();
    }
    private static String resource(OwnerType type) { return type == OwnerType.REQUEST ? "ACTION.APPROVAL_REQUEST" : "ACTION.APPROVAL_TASK"; }
    private static boolean completed(String status) { return List.of("APPROVED", "REJECTED", "WITHDRAWN", "CANCELLED").contains(status); }
    private static void retained(ApprovalDocumentRepository.Head head) {
        if (!head.effectiveHold() && !head.retainUntil().isAfter(Instant.now())) throw ApprovalDocumentCanonical.forbidden();
    }
    private static Tool tool(boolean allowed, String reason) { return new Tool(allowed, allowed ? "ALLOWED" : reason); }
    private static void page(int page, int size) { if (page < 0 || page > 100000 || size < 1 || size > 100) throw ApprovalDocumentCanonical.conflict(); }
}
