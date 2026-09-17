package com.dwp.services.approval.formsv3;

import static com.dwp.services.approval.formsv3.ApprovalFormV3HttpModels.*;
import static com.dwp.services.approval.formsv3.ApprovalFormV3Models.*;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/forms/studio-v3")
@Tag(name = "Approval Form Studio V3", description = "Immutable-schema, draft-only Approval form studio")
public final class ApprovalFormV3Controller {
    private final ApprovalFormV3Service forms;

    public ApprovalFormV3Controller(ApprovalFormV3Service forms) { this.forms = forms; }

    @GetMapping
    @Operation(summary = "List authoritative Form V3 draft workspaces in the current management scope")
    public ApiResponse<WorkspacePage> workspaces(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String lifecycleState,
            @RequestParam(required = false) String afterFormKey,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request,
                Set.of("query", "lifecycleState", "afterFormKey", "limit"));
        return ApiResponse.success(forms.workspaces(
                new WorkspaceFilter(query, lifecycleState, afterFormKey, limit)));
    }

    @GetMapping("/{formId}")
    @Operation(summary = "Read one Form V3 draft workspace")
    public ApiResponse<Workspace> workspace(@PathVariable UUID formId, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        return ApiResponse.success(forms.workspace(formId));
    }

    @GetMapping("/{formId}/versions")
    @Operation(summary = "Read immutable Form V3 version history")
    public ApiResponse<History> history(@PathVariable UUID formId,
            @RequestParam(defaultValue = "50") int size, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of("size"));
        return ApiResponse.success(forms.history(formId, size));
    }

    @GetMapping("/{formId}/versions/diff")
    @Operation(summary = "Compare two immutable Form V3 versions")
    public ApiResponse<VersionDiff> diff(@PathVariable UUID formId,
            @RequestParam int fromVersion, @RequestParam int toVersion, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of("fromVersion", "toVersion"));
        return ApiResponse.success(forms.diff(formId, fromVersion, toVersion));
    }

    @PostMapping("/validate")
    @Operation(summary = "Compile and deterministically validate Form Schema V3 material")
    public ApiResponse<SchemaValidation> validate(@Valid @RequestBody ValidateSchemaRequest input,
            HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        return ApiResponse.success(forms.validateSchema(input.schema()));
    }

    @PostMapping("/{formId}/review")
    @Operation(summary = "Review a draft without publishing or changing lifecycle state")
    public ApiResponse<DraftReview> review(@PathVariable UUID formId, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        return ApiResponse.success(forms.review(formId));
    }

    @PostMapping("/{formId}/evaluate")
    @Operation(summary = "Evaluate a payload with roles from the current trusted Approval context")
    public ApiResponse<ApprovalFormSchemaV3.Evaluation> evaluate(@PathVariable UUID formId,
            @Valid @RequestBody EvaluateRequest input, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        return ApiResponse.success(forms.evaluate(formId, input.payload()));
    }

    @PostMapping("/{sourceFormId}/draft")
    @Operation(summary = "Clone a Form V3 workspace as a new draft", parameters = {
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string", maxLength = 200)),
            @Parameter(name = "X-DWP-Expected-Object-Version", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "integer", format = "int64", minimum = "0", maximum = "9007199254740991"))})
    public ApiResponse<Workspace> cloneDraft(@PathVariable UUID sourceFormId,
            @Valid @RequestBody CloneDraftRequest input, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        ApprovalFormV3HttpBoundary.expectedVersion(request, input.expectedSourceWorkspaceVersion());
        return ApiResponse.success(forms.cloneDraft(sourceFormId, input.toDomain(),
                ApprovalFormV3HttpBoundary.idempotencyKey(request),
                ApprovalFormV3HttpBoundary.correlationId(request)));
    }

    @PutMapping("/{formId}/draft")
    @Operation(summary = "Append a version-fenced immutable Form V3 draft version", parameters = {
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string", maxLength = 200)),
            @Parameter(name = "X-DWP-Expected-Object-Version", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "integer", format = "int64", minimum = "0", maximum = "9007199254740991"))})
    public ApiResponse<Workspace> updateDraft(@PathVariable UUID formId,
            @Valid @RequestBody UpdateDraftRequest input, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        ApprovalFormV3HttpBoundary.expectedVersion(request, input.expectedWorkspaceVersion());
        return ApiResponse.success(forms.update(formId, input.toDomain(),
                ApprovalFormV3HttpBoundary.idempotencyKey(request),
                ApprovalFormV3HttpBoundary.correlationId(request)));
    }

    @PostMapping("/{formId}/fields")
    @Operation(summary = "Append a field through the immutable Form V3 editor")
    public ApiResponse<Workspace> addField(@PathVariable UUID formId,
            @Valid @RequestBody AddFieldRequest input, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        ApprovalFormV3HttpBoundary.expectedVersion(request, input.expectedWorkspaceVersion());
        return ApiResponse.success(forms.addField(formId, input.toDomain(),
                ApprovalFormV3HttpBoundary.idempotencyKey(request),
                ApprovalFormV3HttpBoundary.correlationId(request)));
    }

    @DeleteMapping("/{formId}/fields/{fieldKey}")
    @Operation(summary = "Delete a field through the immutable Form V3 editor")
    public ApiResponse<Workspace> deleteField(@PathVariable UUID formId, @PathVariable String fieldKey,
            @Valid @RequestBody DeleteFieldRequest input, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        ApprovalFormV3HttpBoundary.expectedVersion(request, input.expectedWorkspaceVersion());
        return ApiResponse.success(forms.deleteField(formId, input.toDomain(fieldKey),
                ApprovalFormV3HttpBoundary.idempotencyKey(request),
                ApprovalFormV3HttpBoundary.correlationId(request)));
    }

    @PostMapping("/{formId}/fields/{fieldKey}/clone")
    @Operation(summary = "Clone a field through the immutable Form V3 editor")
    public ApiResponse<Workspace> cloneField(@PathVariable UUID formId, @PathVariable String fieldKey,
            @Valid @RequestBody CloneFieldRequest input, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        ApprovalFormV3HttpBoundary.expectedVersion(request, input.expectedWorkspaceVersion());
        return ApiResponse.success(forms.cloneField(formId, input.toDomain(fieldKey),
                ApprovalFormV3HttpBoundary.idempotencyKey(request),
                ApprovalFormV3HttpBoundary.correlationId(request)));
    }

    @PutMapping("/{formId}/fields/{fieldKey}/properties")
    @Operation(summary = "Update governed field properties through the immutable Form V3 editor")
    public ApiResponse<Workspace> updateFieldProperties(@PathVariable UUID formId,
            @PathVariable String fieldKey, @Valid @RequestBody UpdateFieldPropertiesRequest input,
            HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        ApprovalFormV3HttpBoundary.expectedVersion(request, input.expectedWorkspaceVersion());
        return ApiResponse.success(forms.updateFieldProperties(formId, input.toDomain(fieldKey),
                ApprovalFormV3HttpBoundary.idempotencyKey(request),
                ApprovalFormV3HttpBoundary.correlationId(request)));
    }

    @PutMapping("/{formId}/sections/{sectionKey}/field-order")
    @Operation(summary = "Reorder every field in a Form V3 section")
    public ApiResponse<Workspace> reorderFields(@PathVariable UUID formId,
            @PathVariable String sectionKey, @Valid @RequestBody ReorderFieldsRequest input,
            HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        ApprovalFormV3HttpBoundary.expectedVersion(request, input.expectedWorkspaceVersion());
        return ApiResponse.success(forms.reorderFields(formId, input.toDomain(sectionKey),
                ApprovalFormV3HttpBoundary.idempotencyKey(request),
                ApprovalFormV3HttpBoundary.correlationId(request)));
    }

    @PostMapping("/{formId}/archive")
    @Operation(summary = "Archive a version-fenced Form V3 draft", parameters = {
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string", maxLength = 200)),
            @Parameter(name = "X-DWP-Expected-Object-Version", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "integer", format = "int64", minimum = "0", maximum = "9007199254740991"))})
    public ApiResponse<Workspace> archive(@PathVariable UUID formId,
            @Valid @RequestBody ArchiveDraftRequest input, HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        ApprovalFormV3HttpBoundary.expectedVersion(request, input.expectedWorkspaceVersion());
        return ApiResponse.success(forms.archive(formId, input.toDomain(),
                ApprovalFormV3HttpBoundary.idempotencyKey(request),
                ApprovalFormV3HttpBoundary.correlationId(request)));
    }

    @PostMapping({"/{formId}/publish", "/{formId}/release", "/{formId}/activate"})
    @Operation(hidden = true)
    public ApiResponse<Void> rejectUnsupportedLifecycle(@PathVariable UUID formId,
            HttpServletRequest request) {
        ApprovalFormV3HttpBoundary.query(request, Set.of());
        throw new BaseException(ErrorCode.FORBIDDEN,
                "Form V3 publication and activation are not supported by the draft-only studio.");
    }
}
