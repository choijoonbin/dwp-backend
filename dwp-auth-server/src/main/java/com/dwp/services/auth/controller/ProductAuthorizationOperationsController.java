package com.dwp.services.auth.controller;

import com.dwp.services.auth.config.ProductAuthorizationOperationsSecurityConfig;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.service.ProductAuthorizationContractService;
import com.dwp.services.auth.service.ProductAuthorizationOperationsRequestParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping(
        value = ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX + "/bundles",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class ProductAuthorizationOperationsController {

    private final ProductAuthorizationContractService service;
    private final ProductAuthorizationOperationsRequestParser parser;

    public ProductAuthorizationOperationsController(
            ProductAuthorizationContractService service,
            ProductAuthorizationOperationsRequestParser parser) {
        this.service = service;
        this.parser = parser;
    }

    @GetMapping("/{bundleKey}/active")
    @Operation(
            operationId = "getActiveProductAuthorizationBundlePointerInternal",
            description = "Platform-lane inspection of the exact active pointer and CAS revision. "
                    + "This read does not prove target approval eligibility; callers must also "
                    + "use the exact version preflight. It never changes lifecycle state.")
    public ProductAuthorizationContractDtos.BundleView active(
            @PathVariable String bundleKey,
            @Parameter(description = "Exact platform service identity; validated with the lane token.")
            @RequestHeader(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER)
            String serviceIdentity,
            @Parameter(description = "Purpose-specific platform activation secret.")
            @RequestHeader(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER)
            String activationToken) {
        return service.active(bundleKey);
    }

    @GetMapping("/{bundleKey}/versions/{version}")
    @Operation(
            operationId = "getProductAuthorizationBundleReleasePreflightInternal",
            description = "Platform release preflight for one immutable version/checksum and its "
                    + "exact provider-governed approval evidence. DRAFT and legacy/local approvals "
                    + "fail closed. This read never changes lifecycle state.")
    public ProductAuthorizationContractDtos.GovernedBundlePreflight version(
            @PathVariable String bundleKey,
            @PathVariable long version,
            @Parameter(description = "Exact platform service identity; validated with the lane token.")
            @RequestHeader(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER)
            String serviceIdentity,
            @Parameter(description = "Purpose-specific platform activation secret.")
            @RequestHeader(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER)
            String activationToken) {
        return service.governedReleaseVersion(bundleKey, version);
    }

    @PostMapping(
            value = "/{bundleKey}/versions/{version}/approval",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "approveProductAuthorizationBundleInternal",
            description = "Provider control-plane approval lane. The immutable version and "
                    + "checksum must match a DRAFT, requester and approver must differ, and this "
                    + "operation never activates the bundle.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation =
                                    ProductAuthorizationContractDtos.ApprovalCommand.class))))
    public ProductAuthorizationContractDtos.BundleView approve(
            @PathVariable String bundleKey,
            @PathVariable long version,
            @Parameter(description = "Exact provider service identity; validated with the lane token.")
            @RequestHeader(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER)
            String serviceIdentity,
            @Parameter(description = "Purpose-specific provider approval secret.")
            @RequestHeader(ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER)
            String approvalToken,
            HttpServletRequest request) throws IOException {
        ProductAuthorizationContractDtos.ApprovalCommand command =
                parser.parseApproval(request.getInputStream());
        return service.approveGoverned(
                bundleKey,
                version,
                command.checksum(),
                command.requestedBy(),
                command.approvedBy(),
                command.changeRef());
    }

    @PostMapping(
            value = "/{bundleKey}/versions/{version}/activation",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "activateProductAuthorizationBundleInternal",
            description = "Platform release lane. Activates only the exact independently approved "
                    + "version/checksum with provider maker/checker evidence, a distinct release "
                    + "actor, matching change reference, and compare-and-swap against "
                    + "expectedRevision. It never approves a DRAFT.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation =
                                    ProductAuthorizationContractDtos.ActivationCommand.class))))
    public ProductAuthorizationContractDtos.ActivationResult activate(
            @PathVariable String bundleKey,
            @PathVariable long version,
            @Parameter(description = "Exact platform service identity; validated with the lane token.")
            @RequestHeader(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER)
            String serviceIdentity,
            @Parameter(description = "Purpose-specific platform activation secret.")
            @RequestHeader(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER)
            String activationToken,
            HttpServletRequest request) throws IOException {
        ProductAuthorizationContractDtos.ActivationCommand command =
                parser.parseActivation(request.getInputStream());
        return service.activateGoverned(
                bundleKey,
                version,
                command.checksum(),
                command.activatedBy(),
                command.expectedRevision(),
                command.changeRef());
    }

    @PostMapping(
            value = "/{bundleKey}/versions/{targetVersion}/rollback",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "rollbackProductAuthorizationBundleInternal",
            description = "Platform release lane. Atomically moves the active pointer only to the "
                    + "immediately previous provider-governed approved version after exact checksum, "
                    + "three-party actor separation and CAS validation. No bundle or audit evidence "
                    + "is deleted.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation =
                                    ProductAuthorizationContractDtos.RollbackCommand.class))))
    public ProductAuthorizationContractDtos.ActivationResult rollback(
            @PathVariable String bundleKey,
            @PathVariable long targetVersion,
            @Parameter(description = "Exact platform service identity; validated with the lane token.")
            @RequestHeader(ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER)
            String serviceIdentity,
            @Parameter(description = "Purpose-specific platform activation/rollback secret.")
            @RequestHeader(ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER)
            String activationToken,
            HttpServletRequest request) throws IOException {
        ProductAuthorizationContractDtos.RollbackCommand command =
                parser.parseRollback(request.getInputStream());
        return service.rollbackGoverned(
                bundleKey,
                targetVersion,
                command.checksum(),
                command.rolledBackBy(),
                command.expectedRevision(),
                command.changeRef(),
                command.reason());
    }
}
