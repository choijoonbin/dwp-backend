package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormReferenceDirectory;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import com.dwp.services.approval.security.ApprovalFormReferenceAuthority;
import com.dwp.services.approval.security.ApprovalFormReferenceMutationContext;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** No writes: server normalization and contextual USER validation precede the command's JSON/hash write. */
@Component
public class ApprovalFormReferenceNormalizer {
    private final ApprovalFormReferenceBindingRepository bindings;
    private final ApprovalFormUserBindingRepository published;
    private final ApprovalFormReferenceAuthority authorities;
    private final ApprovalFormReferenceMutationContext context;
    private final ApprovalFormReferenceDirectory directory;
    private final ObjectMapper mapper;

    public ApprovalFormReferenceNormalizer(ApprovalFormReferenceBindingRepository bindings, ApprovalFormUserBindingRepository published,
            ApprovalFormReferenceAuthority authorities, ApprovalFormReferenceMutationContext context,
            ApprovalFormReferenceDirectory directory, ObjectMapper mapper) {
        this.bindings = bindings; this.published = published; this.authorities = authorities;
        this.context = context; this.directory = directory; this.mapper = mapper;
    }

    public Map<String, Object> normalize(Actor actor, UUID requestId, UUID formVersionId, String immutableSchema,
            Map<String, Object> payload, boolean submitting, long expectedVersion, boolean creating, boolean readOnly) {
        var supplied = compile(immutableSchema);
        var pin = creating ? null : bindings.requirePinned(actor, requestId, expectedVersion, null, readOnly);
        FormBinding form = creating ? bindings.requireNewBinding(actor, formVersionId, supplied.sha256()) : pin.binding();
        if (!form.formVersionId().equals(formVersionId) || !form.schemaSha256().equals(supplied.sha256())) throw conflict();
        var schema = creating ? published.requirePublished(form, false) : pin.schema();
        var prepared = new ApprovalFormUserReferences().prepare(schema, payload, submitting);
        Map<String, Object> normalized = prepared.evaluation().payload();
        if (!prepared.references().isEmpty()) {
            if (authorities == null || context == null || directory == null) throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The typed person source is not wired.");
            var pins = context.pins(requestId, expectedVersion, digest(normalized));
            var scoped = new ApprovalFormUserDirectory() {
                @Override public Result search(Authority authority, String query, int size) {
                    throw new BaseException(ErrorCode.FORBIDDEN, "Mutation references are resolve-only.");
                }
                @Override public Result resolve(Authority authority, List<UUID> people) { return directory.resolve(authority, pins, people); }
            };
            normalized = new ApprovalFormUserValidator(scoped, value -> authorities.requireCurrent(value, pins))
                    .validate(form, schema, normalized, submitting).payload();
        }
        if (creating) {
            FormBinding fresh = bindings.requireNewBinding(actor, formVersionId, form.schemaSha256());
            if (!form.equals(fresh) || !published.requirePublished(fresh, false).sha256().equals(schema.sha256())) throw conflict();
        } else {
            var fresh = bindings.requirePinned(actor, requestId, expectedVersion, form.formId(), readOnly);
            if (!pin.binding().equals(fresh.binding()) || !pin.schema().sha256().equals(fresh.schema().sha256())
                    || !pin.dataClassification().equals(fresh.dataClassification())
                    || !pin.workflowVersionId().equals(fresh.workflowVersionId()) || pin.requestVersion() != fresh.requestVersion()) throw conflict();
        }
        return normalized;
    }

    public ApprovalFormReferenceBindingRepository.PinnedForm pinDraft(Actor actor, UUID requestId, long expectedVersion, UUID requestedFormId) {
        return bindings.requirePinned(actor, requestId, expectedVersion, requestedFormId, false);
    }

    private ApprovalFormSchemaV2 compile(String json) {
        try {
            return new ApprovalFormSchemaV2Compiler().compile(mapper.readValue(json, new TypeReference<Map<String, Object>>() { }));
        } catch (Exception exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The immutable typed form cannot be verified.");
        }
    }
    private String digest(Map<String, Object> payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(ApprovalFormSchemaV2Canonical.json(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The normalized mutation payload cannot be sealed.");
        }
    }
    private BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "The exact immutable form snapshot changed."); }
}
