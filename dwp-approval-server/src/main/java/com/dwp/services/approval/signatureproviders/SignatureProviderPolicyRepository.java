package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderPolicyDtos.*;
import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

final class SignatureProviderPolicyRepository {
    record Version(UUID id, long revision, Rules rules, String sha256,
                   long makerUserId, UUID makerPersonId, long editorUserId,
                   UUID editorPersonId, Instant createdAt) { }
    record Publication(long checkerUserId, UUID checkerPersonId, UUID evidenceId,
                       String reviewSha256, Instant publishedAt) { }
    private record Head(UUID policyId, long version, UUID draftVersionId,
                        UUID publishedVersionId) { }
    record State(UUID policyId, long version, Version draft, Version published,
                 Publication publication) {
        UUID expectedDraftSource() { return draft == null ? published == null ? null : published.id() : draft.id(); }
    }

    private final SignatureProviderPersistence persistence;
    private final SignatureProviderPolicyCompiler compiler;

    SignatureProviderPolicyRepository(SignatureProviderPersistence persistence,
                                      SignatureProviderPolicyCompiler compiler) {
        this.persistence = persistence; this.compiler = compiler;
    }

    State require(SignatureProviderCurrentAuthority.Current current, UUID expectedId, boolean lock) {
        persistence.bind(current);
        List<Head> rows = persistence.jdbc().query("""
                SELECT policy_id,version,draft_version_id,published_version_id
                  FROM apr_signature_provider_policy_heads
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                """ + (lock ? " FOR UPDATE" : " FOR SHARE"), persistence.base(current),
                (row, number) -> new Head(row.getObject("policy_id", UUID.class),
                        SignatureProviderModel.version(row.getLong("version")),
                        row.getObject("draft_version_id", UUID.class),
                        row.getObject("published_version_id", UUID.class)));
        if (rows.size() != 1) throw SignatureProviderErrors.hidden();
        Head head = rows.getFirst();
        if (expectedId != null && !expectedId.equals(head.policyId()))
            throw SignatureProviderErrors.hidden();
        Version draft = version(current, head.policyId(), head.draftVersionId());
        Version published = version(current, head.policyId(), head.publishedVersionId());
        Publication publication = published == null ? null
                : publication(current, head.policyId(), published.id());
        if ((draft == null && published == null)
                || (draft != null && published != null && draft.id().equals(published.id())))
            throw SignatureProviderErrors.unavailable();
        return new State(head.policyId(), head.version(), draft, published, publication);
    }

    State optional(SignatureProviderCurrentAuthority.Current current, boolean lock) {
        try { return require(current, null, lock); }
        catch (com.dwp.core.exception.BaseException missing) {
            if (missing.getErrorCode() == com.dwp.core.common.ErrorCode.RESOURCE_NOT_AVAILABLE) return null;
            throw missing;
        }
    }

    View view(SignatureProviderCurrentAuthority.Current current, State state, boolean lockSource) {
        var source = persistence.source(current, lockSource);
        PublishReview review = null;
        if (state.draft() != null) {
            String digest = compiler.reviewDigest(state.policyId(), state.version(), state.draft().id(),
                    state.draft().revision(), state.draft().makerPersonId(), state.draft().editorPersonId(),
                    compiler.compile(state.draft().rules()));
            review = new PublishReview(state.draft().id(), state.version(), state.draft().revision(), digest,
                    GateState.NOT_EVALUATED, List.of("SIGNED_HIGH_AUTHORITY_REQUIRED"), true, null);
        }
        return new View(source.scope(current, persistence.clock().instant()), state.policyId(), state.version(),
                state.draft() == null ? null : draft(state.draft()),
                state.published() == null ? null : published(state.published(), state.publication()), review);
    }

    Policy publishedSource(State state) {
        if (state == null || state.published() == null) {
            return new Policy(PolicySourceState.UNRECORDED, null, null, null, null, null);
        }
        Rules rules = state.published().rules();
        long retentionSeconds;
        try { retentionSeconds = Math.multiplyExact(rules.minimumRetentionDays(), 86_400L); }
        catch (ArithmeticException overflow) { throw SignatureProviderErrors.unavailable(); }
        return new Policy(PolicySourceState.AVAILABLE,
                new SourcePin(state.published().id(), state.published().revision(), state.published().sha256()),
                rules.requiredProviderKinds(), rules.probeMaxAgeSeconds(), rules.probeMaxAgeSeconds(), retentionSeconds);
    }

    Version requirePublishedVersion(SignatureProviderCurrentAuthority.Current current,
            UUID policyId, UUID versionId, String expectedSha256) {
        Version value = version(current, policyId, versionId);
        Publication proof = publication(current, policyId, versionId);
        if (!value.sha256().equals(expectedSha256) || proof == null)
            throw SignatureProviderErrors.conflict();
        return value;
    }

    State initialize(SignatureProviderCurrentAuthority.Current current, Rules rules) {
        var actor = current.actor(); UUID policyId = UUID.randomUUID(), versionId = UUID.randomUUID();
        var compiled = compiler.compile(rules); Instant now = persistence.clock().instant();
        int head = persistence.jdbc().update("""
                INSERT INTO apr_signature_provider_policy_heads(
                    tenant_id,resource_set_key,policy_id,version,draft_version_id)
                VALUES(:tenant,:scope,:policy,0,:versionId)
                ON CONFLICT(tenant_id,resource_set_key) DO NOTHING
                """, persistence.base(current).addValue("policy", policyId).addValue("versionId", versionId));
        if (head != 1) throw SignatureProviderErrors.conflict();
        insertVersion(current, policyId, versionId, 0, compiled, actor.userId(), actor.personPublicId(),
                actor.userId(), actor.personPublicId(), now);
        return require(current, policyId, true);
    }

    State save(SignatureProviderCurrentAuthority.Current current, State state, Rules rules) {
        var actor = current.actor(); var compiled = compiler.compile(rules);
        long revision = persistence.jdbc().queryForObject("""
                SELECT COALESCE(MAX(revision),-1)+1 FROM apr_signature_provider_policy_versions
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND policy_id=:policy
                """, persistence.base(current).addValue("policy", state.policyId()), Long.class);
        SignatureProviderModel.version(revision); UUID versionId = UUID.randomUUID();
        long makerUser = state.draft() == null ? actor.userId() : state.draft().makerUserId();
        UUID makerPerson = state.draft() == null ? actor.personPublicId() : state.draft().makerPersonId();
        insertVersion(current, state.policyId(), versionId, revision, compiled, makerUser, makerPerson,
                actor.userId(), actor.personPublicId(), persistence.clock().instant());
        int changed = persistence.jdbc().update("""
                UPDATE apr_signature_provider_policy_heads
                   SET draft_version_id=:draft,version=version+1
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND policy_id=:policy AND version=:version
                """, persistence.base(current).addValue("draft", versionId).addValue("policy", state.policyId())
                .addValue("version", state.version()));
        if (changed != 1) throw SignatureProviderErrors.conflict();
        return require(current, state.policyId(), true);
    }

    State publish(SignatureProviderCurrentAuthority.Current current, State state, String reviewSha256) {
        if (state.draft() == null || state.draft().makerPersonId().equals(current.actor().personPublicId())
                || state.draft().editorPersonId().equals(current.actor().personPublicId()))
            throw SignatureProviderErrors.forbidden();
        UUID evidence = UUID.randomUUID(); Instant now = persistence.clock().instant();
        int inserted = persistence.jdbc().update("""
                INSERT INTO apr_signature_provider_policy_publications(
                    publication_id,tenant_id,resource_set_key,policy_id,version_id,maker_user_id,
                    maker_person_public_id,editor_user_id,editor_person_public_id,checker_user_id,
                    checker_person_public_id,review_evidence_id,review_content_sha256,published_at)
                VALUES(:publication,:tenant,:scope,:policy,:draft,:makerUser,:makerPerson,:editorUser,
                    :editorPerson,:checkerUser,:checkerPerson,:evidence,:reviewSha,:published)
                """, persistence.base(current).addValue("publication", UUID.randomUUID())
                .addValue("policy", state.policyId()).addValue("draft", state.draft().id())
                .addValue("makerUser", state.draft().makerUserId()).addValue("makerPerson", state.draft().makerPersonId())
                .addValue("editorUser", state.draft().editorUserId()).addValue("editorPerson", state.draft().editorPersonId())
                .addValue("checkerUser", current.actor().userId()).addValue("checkerPerson", current.actor().personPublicId())
                .addValue("evidence", evidence).addValue("reviewSha", reviewSha256)
                .addValue("published", Timestamp.from(now)));
        if (inserted != 1) throw SignatureProviderErrors.conflict();
        int changed = persistence.jdbc().update("""
                UPDATE apr_signature_provider_policy_heads
                   SET published_version_id=draft_version_id,draft_version_id=NULL,version=version+1
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND policy_id=:policy
                   AND version=:version AND draft_version_id=:draft
                """, persistence.base(current).addValue("policy", state.policyId())
                .addValue("version", state.version()).addValue("draft", state.draft().id()));
        if (changed != 1) throw SignatureProviderErrors.conflict();
        return require(current, state.policyId(), true);
    }

    ApprovalSignatureProviderPolicyDtos.History history(
            SignatureProviderCurrentAuthority.Current current, State state, Long beforeRevision) {
        if (beforeRevision != null) SignatureProviderModel.version(beforeRevision);
        var parameters = persistence.base(current).addValue("policy", state.policyId())
                .addValue("before", beforeRevision);
        List<ApprovalSignatureProviderPolicyDtos.HistoryItem> rows = persistence.jdbc().query("""
                SELECT v.*,p.checker_person_public_id,p.review_evidence_id,
                       p.review_content_sha256,p.published_at
                  FROM apr_signature_provider_policy_versions v
                  LEFT JOIN apr_signature_provider_policy_publications p
                    ON p.tenant_id=v.tenant_id AND p.resource_set_key=v.resource_set_key
                   AND p.policy_id=v.policy_id AND p.version_id=v.version_id
                 WHERE v.tenant_id=:tenant AND v.resource_set_key=:scope AND v.policy_id=:policy
                   AND (CAST(:before AS bigint) IS NULL OR v.revision<:before)
                 ORDER BY v.revision DESC LIMIT 51
                """, parameters, (row, number) -> historyItem(row));
        boolean truncated = rows.size() > MAX_HISTORY;
        List<ApprovalSignatureProviderPolicyDtos.HistoryItem> items =
                List.copyOf(rows.subList(0, Math.min(MAX_HISTORY, rows.size())));
        String next = truncated ? Long.toString(items.getLast().revision()) : null;
        return new ApprovalSignatureProviderPolicyDtos.History(
                persistence.source(current, false).scope(current, persistence.clock().instant()),
                state.policyId(), items, next, truncated);
    }

    private Version version(SignatureProviderCurrentAuthority.Current current, UUID policyId, UUID versionId) {
        if (versionId == null) return null;
        var rows = persistence.jdbc().query("""
                SELECT version_id,revision,rules::text,rules_sha256,maker_user_id,
                       maker_person_public_id,editor_user_id,editor_person_public_id,created_at
                  FROM apr_signature_provider_policy_versions
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND policy_id=:policy AND version_id=:versionId
                """, persistence.base(current).addValue("policy", policyId).addValue("versionId", versionId),
                (row, number) -> {
            Rules rules = persistence.canonical().read(row.getString("rules"), Rules.class);
            var compiled = compiler.compile(rules);
            if (!compiled.sha256().equals(row.getString("rules_sha256"))) throw SignatureProviderErrors.unavailable();
            return new Version(versionId, SignatureProviderModel.version(row.getLong("revision")),
                    rules, compiled.sha256(),
                    row.getLong("maker_user_id"), row.getObject("maker_person_public_id", UUID.class),
                    row.getLong("editor_user_id"), row.getObject("editor_person_public_id", UUID.class),
                    row.getTimestamp("created_at").toInstant());
        });
        if (rows.size() != 1) throw SignatureProviderErrors.unavailable();
        return rows.getFirst();
    }

    private Publication publication(SignatureProviderCurrentAuthority.Current current, UUID policyId, UUID versionId) {
        var rows = persistence.jdbc().query("""
                SELECT checker_user_id,checker_person_public_id,review_evidence_id,
                       review_content_sha256,published_at
                  FROM apr_signature_provider_policy_publications
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND policy_id=:policy AND version_id=:versionId
                """, persistence.base(current).addValue("policy", policyId).addValue("versionId", versionId),
                (row, number) -> new Publication(row.getLong("checker_user_id"),
                        row.getObject("checker_person_public_id", UUID.class),
                        row.getObject("review_evidence_id", UUID.class), row.getString("review_content_sha256"),
                        row.getTimestamp("published_at").toInstant()));
        if (rows.size() != 1) throw SignatureProviderErrors.unavailable();
        return rows.getFirst();
    }

    private void insertVersion(SignatureProviderCurrentAuthority.Current current, UUID policyId,
            UUID versionId, long revision, SignatureProviderPolicyCompiler.Compiled compiled,
            long makerUser, UUID makerPerson, long editorUser, UUID editorPerson, Instant created) {
        int inserted = persistence.jdbc().update("""
                INSERT INTO apr_signature_provider_policy_versions(
                    tenant_id,resource_set_key,policy_id,version_id,revision,rules,rules_sha256,
                    maker_user_id,maker_person_public_id,editor_user_id,editor_person_public_id,created_at)
                VALUES(:tenant,:scope,:policy,:versionId,:revision,CAST(:rules AS jsonb),:sha,
                    :makerUser,:makerPerson,:editorUser,:editorPerson,:created)
                """, persistence.base(current).addValue("policy", policyId).addValue("versionId", versionId)
                .addValue("revision", revision).addValue("rules", compiled.canonicalJson())
                .addValue("sha", compiled.sha256()).addValue("makerUser", makerUser)
                .addValue("makerPerson", makerPerson).addValue("editorUser", editorUser)
                .addValue("editorPerson", editorPerson).addValue("created", Timestamp.from(created)));
        if (inserted != 1) throw SignatureProviderErrors.conflict();
    }

    private Draft draft(Version value) {
        return new Draft(value.id(), value.revision(), value.rules(), value.sha256(),
                value.makerPersonId(), value.editorPersonId(), value.createdAt());
    }

    private Published published(Version value, Publication publication) {
        if (publication == null) throw SignatureProviderErrors.unavailable();
        return new Published(value.id(), value.revision(), value.rules(), value.sha256(),
                value.makerPersonId(), value.editorPersonId(), publication.checkerPersonId(),
                publication.evidenceId(), publication.reviewSha256(), publication.publishedAt());
    }

    private ApprovalSignatureProviderPolicyDtos.HistoryItem historyItem(
            java.sql.ResultSet row) throws java.sql.SQLException {
        Rules rules = persistence.canonical().read(row.getString("rules"), Rules.class);
        String sha = compiler.compile(rules).sha256();
        if (!sha.equals(row.getString("rules_sha256"))) throw SignatureProviderErrors.unavailable();
        Instant published = row.getTimestamp("published_at") == null ? null : row.getTimestamp("published_at").toInstant();
        return new ApprovalSignatureProviderPolicyDtos.HistoryItem(
                row.getObject("version_id", UUID.class), row.getLong("revision"),
                published == null ? "DRAFT" : "PUBLISHED", rules, sha,
                row.getObject("maker_person_public_id", UUID.class), row.getObject("editor_person_public_id", UUID.class),
                row.getObject("checker_person_public_id", UUID.class), row.getObject("review_evidence_id", UUID.class),
                row.getString("review_content_sha256"), row.getTimestamp("created_at").toInstant(), published);
    }
}
