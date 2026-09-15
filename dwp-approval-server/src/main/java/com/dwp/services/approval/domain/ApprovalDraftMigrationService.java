package com.dwp.services.approval.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ApprovalDraftMigrationService {
    private static final String VIEW = "ACTION.APPROVAL_REQUEST:VIEW";
    private static final String UPDATE = "ACTION.APPROVAL_REQUEST:UPDATE";
    private static final String CREATE = "ACTION.APPROVAL_REQUEST:CREATE";

    private final ApprovalDraftMigrationRepository migrations;
    private final ApprovalDraftMigrationMapper mapper;
    private final ApprovalDraftRepository drafts;
    private final ApprovalWorkAuthority authority;
    private final ApprovalService approvals;
    private final AuditOutboxRecorder audit;

    public ApprovalDraftMigrationService(
            ApprovalDraftMigrationRepository migrations,
            ApprovalDraftMigrationMapper mapper,
            ApprovalDraftRepository drafts,
            ApprovalWorkAuthority authority,
            ApprovalService approvals,
            AuditOutboxRecorder audit) {
        this.migrations = migrations;
        this.mapper = mapper;
        this.drafts = drafts;
        this.authority = authority;
        this.approvals = approvals;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApprovalDraftMigrationDtos.Preview preview(
            UUID sourceRequestId, UUID targetFormId, UUID targetWorkflowId) {
        requireIdentifiers(sourceRequestId, targetFormId, targetWorkflowId);
        ApprovalRequestContext.Actor actor = authority.require(VIEW, true);
        authority.requireCurrent(CREATE);
        ApprovalDraftMigrationRepository.Source source =
                migrations.source(actor, sourceRequestId, null, false);
        ApprovalDraftMigrationRepository.Target target = migrations.target(
                actor, source.resourceSet(), targetFormId, targetWorkflowId, false);
        ApprovalDraftMigrationMapper.Mapping mapping = mapping(source, target);
        authority.require(VIEW, true);
        authority.requireCurrent(CREATE);
        return preview(source, target, mapping, Instant.now());
    }

    @Transactional
    public ApprovalDraftMigrationDtos.Result migrate(
            UUID sourceRequestId,
            ApprovalDraftMigrationDtos.MigrateRequest body,
            String idempotencyKey,
            String correlationId) {
        validate(sourceRequestId, body, idempotencyKey);
        ApprovalRequestContext.Actor actor = authority.require(UPDATE, true);
        ApprovalDraftMigrationRepository.Source source = migrations.source(
                actor, sourceRequestId, body.expectedVersion(), true);
        authority.requireCurrent(CREATE);
        String route = "POST /v1/requests/" + sourceRequestId + "/draft/migrate";
        ApprovalDraftRepository.Receipt receipt = drafts.begin(actor, route, "CREATE",
                sourceRequestId, idempotencyKey, body, body.expectedVersion(), null, correlationId);
        if (receipt.replay()) {
            ApprovalDraftMigrationDtos.Result replay = drafts.read(
                    receipt.result(), ApprovalDraftMigrationDtos.Result.class);
            drafts.lock(actor, replay.draft().requestId());
            authority.require(UPDATE, true);
            authority.requireCurrent(CREATE);
            return replay;
        }

        ApprovalDraftMigrationRepository.Target target = migrations.target(actor, source.resourceSet(),
                body.targetFormId(), body.targetWorkflowId(), true);
        requireExactTarget(body, target.binding());
        ApprovalDraftMigrationMapper.Mapping mapping = mapping(source, target);
        if (!source.requiresMigration() || sameBinding(source.binding(), target.binding())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The source draft does not require this form migration.");
        }
        if (!mapping.routeCompatible()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The migrated draft does not match the selected current workflow route.");
        }

        ApprovalDtos.CreateRequest create = new ApprovalDtos.CreateRequest(
                target.binding().workflowId(), target.binding().formId(), source.title(),
                source.summary(), source.priority(), mapping.payload());
        ApprovalDtos.RequestSummary draft = approvals.create(create, correlationId);
        ApprovalDraftMigrationRepository.Source created = migrations.source(
                actor, draft.requestId(), draft.version(), false);
        requireCreatedBinding(created.binding(), target.binding());
        authority.require(UPDATE, true);
        authority.requireCurrent(CREATE);

        ApprovalDraftMigrationDtos.Result result = new ApprovalDraftMigrationDtos.Result(
                draft, source.requestId(), source.version(), target.binding(), mapping.mappedFields(),
                mapping.droppedFields(), mapping.incompatibleFields(),
                mapping.requiredFieldsToComplete());
        recordEvents(actor, source, draft, target.binding(), mapping, body.reason(), correlationId);
        ApprovalWorkDtos.DraftState state = drafts.state(actor, draft.requestId(), false);
        recordAudit(actor, source, state, target.binding(), mapping, body.reason(), correlationId);
        drafts.complete(receipt, result, state);
        return result;
    }

    private ApprovalDraftMigrationMapper.Mapping mapping(
            ApprovalDraftMigrationRepository.Source source,
            ApprovalDraftMigrationRepository.Target target) {
        Map<String, Object> payload = new LinkedHashMap<>(source.payload());
        payload.put("summary", source.summary());
        return mapper.map(source.requestId(), payload, target);
    }

    private ApprovalDraftMigrationDtos.Preview preview(
            ApprovalDraftMigrationRepository.Source source,
            ApprovalDraftMigrationRepository.Target target,
            ApprovalDraftMigrationMapper.Mapping mapping,
            Instant evaluatedAt) {
        return new ApprovalDraftMigrationDtos.Preview(
                source.requestId(), source.version(), source.binding(), target.binding(),
                source.requiresMigration() && !sameBinding(source.binding(), target.binding()),
                mapping.routeCompatible(), mapping.mappedFields(), mapping.droppedFields(),
                mapping.incompatibleFields(), mapping.requiredFieldsToComplete(), evaluatedAt);
    }

    private void recordEvents(
            ApprovalRequestContext.Actor actor,
            ApprovalDraftMigrationRepository.Source source,
            ApprovalDtos.RequestSummary draft,
            ApprovalDraftMigrationDtos.Binding target,
            ApprovalDraftMigrationMapper.Mapping mapping,
            String reason,
            String correlationId) {
        Map<String, Object> evidence = evidence(source, draft.requestId(), target, mapping);
        drafts.event(actor, source.requestId(), "REQUEST_DRAFT_MIGRATED", reason.trim(),
                correlationId, evidence);
        drafts.event(actor, draft.requestId(), "REQUEST_DRAFT_CREATED_FROM_MIGRATION",
                reason.trim(), correlationId, evidence);
    }

    private void recordAudit(
            ApprovalRequestContext.Actor actor,
            ApprovalDraftMigrationRepository.Source source,
            ApprovalWorkDtos.DraftState draft,
            ApprovalDraftMigrationDtos.Binding target,
            ApprovalDraftMigrationMapper.Mapping mapping,
            String reason,
            String correlationId) {
        Map<String, Object> after = new LinkedHashMap<>(
                evidence(source, draft.requestId(), target, mapping));
        after.put("draftVersion", draft.version());
        after.put("payloadRevision", draft.payloadRevision());
        after.put("reason", reason.trim());
        audit.record(AuditEvent.builder().tenantId(actor.tenantId()).category("SYSTEM_EVENT")
                .action("approval.request.draft.migrated").outcome("SUCCESS").severity("INFO")
                .actorType("USER").actorId(actor.userId().toString())
                .actorRoles(java.util.List.copyOf(actor.roles()))
                .sourceService("dwp-approval-server").sourceModule("approval-draft-migration")
                .targetType("APPROVAL_REQUEST").targetId(draft.requestId().toString())
                .approvalId(draft.requestId().toString()).correlationId(correlationId)
                .retentionClass("EXTENDED").afterState(after).build());
    }

    private Map<String, Object> evidence(
            ApprovalDraftMigrationRepository.Source source,
            UUID draftId,
            ApprovalDraftMigrationDtos.Binding target,
            ApprovalDraftMigrationMapper.Mapping mapping) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceRequestId", source.requestId().toString());
        evidence.put("sourceVersion", source.version());
        evidence.put("sourceFormVersionId", source.binding().formVersionId().toString());
        evidence.put("targetRequestId", draftId.toString());
        evidence.put("targetFormVersionId", target.formVersionId().toString());
        evidence.put("targetFormSchemaSha256", target.formSchemaSha256());
        evidence.put("targetWorkflowVersionId", target.workflowVersionId().toString());
        evidence.put("targetWorkflowDefinitionSha256", target.workflowDefinitionSha256());
        evidence.put("mappedFields", mapping.mappedFields());
        evidence.put("droppedFields", mapping.droppedFields());
        evidence.put("incompatibleFields", mapping.incompatibleFields());
        evidence.put("requiredFieldsToComplete", mapping.requiredFieldsToComplete());
        return Map.copyOf(evidence);
    }

    private void requireExactTarget(
            ApprovalDraftMigrationDtos.MigrateRequest request,
            ApprovalDraftMigrationDtos.Binding target) {
        if (!request.targetFormId().equals(target.formId())
                || !request.targetFormVersionId().equals(target.formVersionId())
                || !request.targetFormSchemaSha256().equals(target.formSchemaSha256())
                || !request.targetWorkflowId().equals(target.workflowId())
                || !request.targetWorkflowVersionId().equals(target.workflowVersionId())
                || !request.targetWorkflowDefinitionSha256()
                .equals(target.workflowDefinitionSha256())) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The migration target changed after preview.");
        }
    }

    private void requireCreatedBinding(
            ApprovalDraftMigrationDtos.Binding created,
            ApprovalDraftMigrationDtos.Binding target) {
        if (!created.formId().equals(target.formId())
                || !created.formVersionId().equals(target.formVersionId())
                || !created.formSchemaSha256().equals(target.formSchemaSha256())
                || !created.workflowId().equals(target.workflowId())
                || !created.workflowVersionId().equals(target.workflowVersionId())
                || !created.workflowDefinitionSha256().equals(target.workflowDefinitionSha256())) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The created draft did not retain the previewed migration binding.");
        }
    }

    private boolean sameBinding(
            ApprovalDraftMigrationDtos.Binding source,
            ApprovalDraftMigrationDtos.Binding target) {
        return source.formVersionId().equals(target.formVersionId())
                && source.workflowVersionId().equals(target.workflowVersionId());
    }

    private void validate(UUID sourceRequestId, ApprovalDraftMigrationDtos.MigrateRequest body,
                          String idempotencyKey) {
        if (body == null || body.expectedVersion() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid draft migration request.");
        }
        requireIdentifiers(sourceRequestId, body.targetFormId(), body.targetWorkflowId());
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,120}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid migration command key.");
        }
    }

    private void requireIdentifiers(UUID requestId, UUID formId, UUID workflowId) {
        if (requestId == null || formId == null || workflowId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid migration target.");
        }
    }
}
