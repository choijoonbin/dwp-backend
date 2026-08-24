package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.ProductSurfaceStepUpChallengeService;
import com.dwp.services.auth.service.ProductSurfaceStepUpRequestParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/product-surface-step-up-challenges")
public class ProductSurfaceStepUpController {

    private final ProductSurfaceStepUpRequestParser parser;
    private final ProductSurfaceStepUpChallengeService service;

    public ProductSurfaceStepUpController(
            ProductSurfaceStepUpRequestParser parser,
            ProductSurfaceStepUpChallengeService service) {
        this.parser = parser;
        this.service = service;
    }

    @PostMapping
    @Operation(
            operationId = "issueProductSurfaceStepUpChallenge",
            summary = "Issue a command-bound product step-up challenge",
            parameters = {
                    @Parameter(
                            name = "X-CSRF-TOKEN",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Gateway-validated CSRF token."),
                    @Parameter(
                            name = "X-DWP-Expected-Decision-Revision",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Exact current composite authorization revision.",
                            schema = @Schema(type = "string", maxLength = 200))
            },
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            implementation = ProductSurfaceStepUpDtos.IssueRequest.class))))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "A single-use signed challenge was issued.",
                    content = @Content(schema = @Schema(
                            implementation = ProductSurfaceStepUpOpenApi.IssuedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "The exact command binding is malformed.",
                    content = @Content(schema = @Schema(
                            implementation = ProductSurfaceStepUpOpenApi.ValidationError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "An authenticated session is required.",
                    content = @Content(schema = @Schema(
                            implementation = ProductSurfaceStepUpOpenApi.AuthenticationError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Fresh assurance is required or the command is forbidden.",
                    content = @Content(schema = @Schema(oneOf = {
                            ProductSurfaceStepUpOpenApi.RequiredResponse.class,
                            ProductSurfaceStepUpOpenApi.ForbiddenError.class
                    }))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "The decision revision or command binding changed.",
                    content = @Content(schema = @Schema(
                            implementation = ProductSurfaceStepUpOpenApi.ConflictError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "Authoritative route, scope, provider, or signing evidence is unavailable.",
                    content = @Content(schema = @Schema(
                            implementation = ProductSurfaceStepUpOpenApi.AuthorityUnavailableError.class)))
    })
    public ResponseEntity<ApiResponse<?>> issue(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Parameter(hidden = true)
            @RequestHeader(value = "X-DWP-Expected-Decision-Revision", required = false)
            String expectedDecisionRevision,
            @RequestBody String body,
            HttpServletResponse servletResponse) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new BaseException(ErrorCode.AUTH_REQUIRED);
        }
        long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        ProductSurfaceStepUpChallengeService.Outcome outcome = service.issue(
                actorId, tenantId, jwt, parser.parse(body), expectedDecisionRevision,
                servletResponse);
        if (outcome instanceof ProductSurfaceStepUpChallengeService.Issued issued) {
            return ResponseEntity.ok(ApiResponse.success(issued.response()));
        }
        ProductSurfaceStepUpChallengeService.Continuation continuation =
                (ProductSurfaceStepUpChallengeService.Continuation) outcome;
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(
                ErrorCode.STEP_UP_REQUIRED,
                ErrorCode.STEP_UP_REQUIRED.getMessage(),
                continuation.response(),
                correlationId));
    }
}
