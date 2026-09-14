package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalFormUserCurrentAuthority;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ApprovalFormUserCandidateService {
    private final ApprovalFormUserBindingRepository forms;
    private final ApprovalFormUserDirectory.AuthorityProvider authorities;
    private final ApprovalFormUserDirectory directory;
    private final ApprovalFormReferenceBindingRepository references;

    public ApprovalFormUserCandidateService(ApprovalFormUserBindingRepository forms,
            ApprovalFormUserDirectory.AuthorityProvider authorities, ApprovalFormUserDirectory directory) {
        this(forms, authorities, directory, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalFormUserCandidateService(ApprovalFormUserBindingRepository forms,
            ApprovalFormUserDirectory.AuthorityProvider authorities, ApprovalFormUserDirectory directory,
            ApprovalFormReferenceBindingRepository references) {
        this.forms = forms;
        this.authorities = authorities;
        this.directory = directory;
        this.references = references;
    }

    public Candidates search(UUID formId, UUID formVersionId, String schemaSha256, String groupKey,
            String fieldKey, String query, int size, boolean admin) {
        return search(formId, formVersionId, schemaSha256, groupKey, fieldKey, query, size, admin, null);
    }

    public Candidates search(UUID formId, UUID formVersionId, String schemaSha256, String groupKey,
            String fieldKey, String query, int size, boolean admin, UUID requestId) {
        if (formId == null || formVersionId == null || schemaSha256 == null || !schemaSha256.matches("[a-f0-9]{64}")
                || query == null || !query.equals(query.strip()) || query.length() < 2 || query.length() > 100
                || query.codePoints().anyMatch(Character::isISOControl) || size < 1 || size > 30 || admin && requestId != null) throw invalid();
        var actor = ApprovalRequestContext.require();
        var binding = new FormBinding(actor.tenantId(), actor.userId(), formId, formVersionId, schemaSha256);
        Authority authority = authorities.requireCurrent(binding);
        String route = admin ? ApprovalFormUserCurrentAuthority.ADMIN_ROUTE : ApprovalFormUserCurrentAuthority.WORK_ROUTE;
        if (authority == null || !binding.equals(authority.form()) || !route.equals(authority.routeContractKey())
                || !ApprovalFormUserDirectory.SOURCE_POLICY.equals(authority.sourcePolicyKey())
                || authority.validUntil() == null || !OffsetDateTime.now().isBefore(authority.validUntil())) throw unavailable();
        var pin = requestId == null ? null : pinned(actor, requestId, binding);
        var compiled = pin == null ? forms.requirePublished(binding, admin) : pin.schema();
        String path = new ApprovalFormUserReferences().requireUserField(compiled, groupKey, fieldKey);
        same(authority, authorities.requireCurrent(binding));
        var result = directory.search(authority, query, size);
        if (result == null || result.people().size() > size) throw unavailable();
        same(authority, result.authority());
        var ids = new HashSet<UUID>();
        var subjects = new HashSet<Long>();
        for (var person : result.people()) {
            if (person.tenantId() == null || person.tenantId() != actor.tenantId().longValue()
                    || person.subjectId() == null || person.subjectId() <= 0 || !subjects.add(person.subjectId())
                    || person.personPublicId() == null || !ids.add(person.personPublicId())
                    || !"TENANT".equals(person.identityPlane()) || !"ACTIVE".equals(person.status())
                    || person.displayName() == null || person.displayName().isBlank() || person.displayName().length() > 200) {
                throw unavailable();
            }
        }
        if (requestId == null) forms.requirePublished(binding, admin);
        else {
            var fresh = pinned(actor, requestId, binding);
            if (pin.requestVersion() != fresh.requestVersion() || !pin.binding().equals(fresh.binding())
                    || !pin.workflowVersionId().equals(fresh.workflowVersionId())
                    || !pin.dataClassification().equals(fresh.dataClassification())) throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The owned candidate request snapshot changed.");
        }
        same(authority, authorities.requireCurrent(binding));
        if (!OffsetDateTime.now().isBefore(authority.validUntil())) throw unavailable();
        return new Candidates(formVersionId, schemaSha256, path, authority.decisionRevision(), authority.validUntil(),
                result.people().stream().map(person -> new Candidate(person.personPublicId(), person.displayName())).toList(),
                result.people().size() == size, requestId, pin == null ? null : pin.requestVersion());
    }

    private ApprovalFormReferenceBindingRepository.PinnedForm pinned(ApprovalRequestContext.Actor actor, UUID requestId, FormBinding form) {
        if (references == null) throw unavailable();
        return references.requireCandidatePinned(actor, requestId, form.formId(), form.formVersionId(), form.schemaSha256());
    }

    private void same(Authority expected, Authority actual) {
        if (actual == null) throw unavailable();
        if (!expected.equals(actual)) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                "The person source authority changed; refresh before retrying.");
    }

    private BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A bounded typed person search is required."); }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Current typed person candidates are unavailable."); }

    @Schema(name = "ApprovalFormUserCandidate", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Candidate(UUID personPublicId, String displayName) { }

    @Schema(name = "ApprovalFormUserCandidates", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Candidates(UUID formVersionId, String schemaSha256, String fieldPath, String decisionRevision,
            OffsetDateTime validUntil, List<Candidate> people, boolean mayBeTruncated,
            @Schema(nullable = true) UUID requestId, @Schema(nullable = true) Long requestVersion) {
        public Candidates { people = List.copyOf(people); }
        public Candidates(UUID formVersionId, String schemaSha256, String fieldPath, String decisionRevision,
                OffsetDateTime validUntil, List<Candidate> people, boolean mayBeTruncated) {
            this(formVersionId, schemaSha256, fieldPath, decisionRevision, validUntil, people, mayBeTruncated, null, null);
        }
    }
}
