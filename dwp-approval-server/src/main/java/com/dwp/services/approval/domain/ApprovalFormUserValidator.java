package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.AuthorityProvider;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Contextual checks remain separate from the immutable pure schema and legacy USER semantics. */
public final class ApprovalFormUserValidator {

    private final ApprovalFormUserDirectory directory;
    private final AuthorityProvider authorities;
    private final Clock clock;
    private final ApprovalFormUserReferences references = new ApprovalFormUserReferences();

    public ApprovalFormUserValidator(ApprovalFormUserDirectory directory, AuthorityProvider authorities) {
        this(directory, authorities, Clock.systemUTC());
    }

    public ApprovalFormUserValidator(ApprovalFormUserDirectory directory, AuthorityProvider authorities, Clock clock) {
        this.directory = Objects.requireNonNull(directory);
        this.authorities = Objects.requireNonNull(authorities);
        this.clock = Objects.requireNonNull(clock);
    }

    public ApprovalFormSchemaV2.Evaluation validate(FormBinding form, ApprovalFormSchemaV2 schema,
            Map<String, Object> input, boolean submitting) {
        requireForm(form, schema);
        Authority authority = current(form);
        var prepared = references.prepare(schema, input, submitting);
        List<UUID> people = prepared.distinctPersonIds();
        Set<Long> subjects = new HashSet<>();
        for (int offset = 0; offset < people.size(); offset += ApprovalFormUserDirectory.MAX_SIZE) {
            sameAuthority(authority, current(form));
            List<UUID> batch = List.copyOf(people.subList(offset,
                    Math.min(people.size(), offset + ApprovalFormUserDirectory.MAX_SIZE)));
            requireResolved(authority, batch, directory.resolve(authority, batch), subjects);
        }
        sameAuthority(authority, current(form));
        return prepared.evaluation();
    }

    private void requireForm(FormBinding form, ApprovalFormSchemaV2 schema) {
        if (form == null || schema == null || form.tenantId() <= 0 || form.actorId() <= 0
                || form.formId() == null || form.formVersionId() == null
                || !schema.sha256().equals(form.schemaSha256())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The typed form snapshot no longer matches.");
        }
    }

    private Authority current(FormBinding form) {
        Authority authority = authorities.requireCurrent(form);
        if (authority == null || !form.equals(authority.form())
                || !ApprovalFormUserDirectory.SOURCE_POLICY.equals(authority.sourcePolicyKey())
                || !bounded(authority.contextKey()) || !bounded(authority.contextScopeKey())
                || !bounded(authority.decisionRevision()) || !bounded(authority.routeContractKey())
                || authority.validUntil() == null
                || !OffsetDateTime.now(clock).isBefore(authority.validUntil())) throw unavailable();
        return authority;
    }

    private void requireResolved(Authority authority, List<UUID> expected, ApprovalFormUserDirectory.Result result,
            Set<Long> subjects) {
        if (result == null || result.authority() == null) throw unavailable();
        sameAuthority(authority, result.authority());
        Set<UUID> remaining = new HashSet<>(expected);
        if (result.people().size() != expected.size()) throw new BaseException(ErrorCode.FORBIDDEN,
                "Selected people did not resolve to an exact current source set.");
        for (var person : result.people()) {
            if (person.tenantId() == null || person.tenantId() != authority.form().tenantId()
                    || person.subjectId() == null || person.subjectId() <= 0 || !subjects.add(person.subjectId())
                    || person.personPublicId() == null || !remaining.remove(person.personPublicId())
                    || !"TENANT".equals(person.identityPlane()) || !"ACTIVE".equals(person.status())) {
                throw new BaseException(ErrorCode.FORBIDDEN, "A selected person is not a current scoped tenant coworker.");
            }
        }
        if (!remaining.isEmpty()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "A selected person is no longer available in the form source.");
        }
    }

    private void sameAuthority(Authority expected, Authority actual) {
        if (!expected.equals(actual)) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                "The typed form directory authority changed; refresh before retrying.");
    }

    private boolean bounded(String value) { return value != null && !value.isBlank() && value.length() <= 512; }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Current exact typed form directory authority is unavailable.");
    }
}
