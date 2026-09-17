package com.dwp.services.approval.templates;

import static com.dwp.services.approval.templates.ApprovalTemplateHttpModels.*;
import static com.dwp.services.approval.templates.ApprovalTemplateModels.*;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/forms/templates")
@Tag(name = "Approval form templates", description = "Global and tenant template catalog; all writes remain draft-only")
public final class ApprovalTemplateController {
    private final ApprovalTemplateService templates;

    public ApprovalTemplateController(ApprovalTemplateService templates) { this.templates = templates; }

    @GetMapping
    @Operation(summary = "List effective global and tenant Approval templates")
    public ApiResponse<CatalogPage> catalog(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String categoryKey,
            @RequestParam(required = false) String ownerGroupRef,
            @RequestParam(required = false) String locale,
            @RequestParam(name = "tag", required = false) List<String> tags,
            @RequestParam(required = false) String scopeKind,
            @RequestParam(required = false) String afterTemplateKey,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        ApprovalTemplateHttpBoundary.query(request,
                Set.of("query", "categoryKey", "ownerGroupRef", "locale", "tag", "scopeKind",
                        "afterTemplateKey", "limit"), Set.of("tag"));
        return ApiResponse.success(templates.catalog(new CatalogFilter(query, categoryKey, ownerGroupRef,
                locale, tags == null ? Set.of() : new LinkedHashSet<>(tags), scopeKind, afterTemplateKey, limit)));
    }

    @GetMapping("/{templateId}")
    @Operation(summary = "Read one effective Approval template and its immutable dependencies")
    public ApiResponse<Template> template(@PathVariable UUID templateId, HttpServletRequest request) {
        ApprovalTemplateHttpBoundary.query(request, Set.of(), Set.of());
        return ApiResponse.success(templates.template(templateId));
    }

    @GetMapping("/{templateId}/comparison")
    @Operation(summary = "Compare an installed template version with the current released version")
    public ApiResponse<UpdateComparison> compare(@PathVariable UUID templateId,
            @RequestParam int installedVersion, HttpServletRequest request) {
        ApprovalTemplateHttpBoundary.query(request, Set.of("installedVersion"), Set.of());
        return ApiResponse.success(templates.compare(templateId, installedVersion));
    }

    @GetMapping("/versions/{templateVersionId}/preview")
    @Operation(summary = "Preview one immutable template version for a governed viewport")
    public ApiResponse<TemplatePreview> preview(@PathVariable UUID templateVersionId,
            @RequestParam(defaultValue = "DESKTOP") String viewport, HttpServletRequest request) {
        ApprovalTemplateHttpBoundary.query(request, Set.of("viewport"), Set.of());
        return ApiResponse.success(templates.preview(templateVersionId, viewport));
    }

    @PostMapping("/packages/import")
    @Operation(summary = "Validate and import a signed-hash template package as a tenant draft",
            description = "The package is immutable evidence and never publishes or activates the imported template.",
            parameters = {
                    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                            schema = @Schema(type = "string", maxLength = 200)),
                    @Parameter(name = "X-DWP-Expected-Object-Version", in = ParameterIn.HEADER, required = true,
                            schema = @Schema(type = "integer", format = "int64", minimum = "0", maximum = "0"))})
    public ApiResponse<PackageImport> importPackage(@Valid @RequestBody ImportPackageRequest input,
            HttpServletRequest request) {
        ApprovalTemplateHttpBoundary.query(request, Set.of(), Set.of());
        ApprovalTemplateHttpBoundary.expectedVersion(request, input.expectedTemplateVersion());
        return ApiResponse.success(templates.importPackage(input.toDomain(),
                ApprovalTemplateHttpBoundary.idempotencyKey(request),
                ApprovalTemplateHttpBoundary.correlationId(request)));
    }

    @PostMapping("/{templateId}/draft")
    @Operation(summary = "Clone a released template into a tenant-scoped template draft",
            parameters = {
                    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                            schema = @Schema(type = "string", maxLength = 200)),
                    @Parameter(name = "X-DWP-Expected-Object-Version", in = ParameterIn.HEADER, required = true,
                            schema = @Schema(type = "integer", format = "int64", minimum = "0",
                                    maximum = "9007199254740991"))})
    public ApiResponse<Template> cloneDraft(@PathVariable UUID templateId,
            @Valid @RequestBody CloneDraftRequest input, HttpServletRequest request) {
        ApprovalTemplateHttpBoundary.query(request, Set.of(), Set.of());
        ApprovalTemplateHttpBoundary.expectedVersion(request, input.expectedTemplateVersion());
        return ApiResponse.success(templates.cloneTemplate(templateId, input.toDomain(),
                ApprovalTemplateHttpBoundary.idempotencyKey(request),
                ApprovalTemplateHttpBoundary.correlationId(request)));
    }

    @PostMapping("/versions/{templateVersionId}/install")
    @Operation(summary = "Install one immutable released template version as a Form V3 draft",
            description = "This operation never publishes or activates a form.",
            parameters = {
                    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                            schema = @Schema(type = "string", maxLength = 200)),
                    @Parameter(name = "X-DWP-Expected-Object-Version", in = ParameterIn.HEADER, required = true,
                            schema = @Schema(type = "integer", format = "int64", minimum = "0",
                                    maximum = "9007199254740991"))})
    public ApiResponse<Installation> install(@PathVariable UUID templateVersionId,
            @Valid @RequestBody InstallDraftRequest input, HttpServletRequest request) {
        ApprovalTemplateHttpBoundary.query(request, Set.of(), Set.of());
        ApprovalTemplateHttpBoundary.expectedVersion(request, input.expectedTemplateVersion());
        return ApiResponse.success(templates.install(templateVersionId, input.toDomain(),
                ApprovalTemplateHttpBoundary.idempotencyKey(request),
                ApprovalTemplateHttpBoundary.correlationId(request)));
    }

    @PostMapping({"/{templateId}/publish", "/{templateId}/release", "/{templateId}/activate"})
    @Operation(hidden = true)
    public ApiResponse<Void> rejectUnsupportedLifecycle(@PathVariable UUID templateId,
            HttpServletRequest request) {
        ApprovalTemplateHttpBoundary.query(request, Set.of(), Set.of());
        throw new BaseException(ErrorCode.FORBIDDEN,
                "Template publication and activation are not supported by the draft-only catalog.");
    }
}
