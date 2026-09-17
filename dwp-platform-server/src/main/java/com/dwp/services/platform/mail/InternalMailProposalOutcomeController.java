package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/mail/proposal-outcomes")
public final class InternalMailProposalOutcomeController {

    static final String PEOPLE_SERVICE_IDENTITY = "dwp-people-server";

    private final MailProposalOutcomePort outcomes;

    public InternalMailProposalOutcomeController(MailProposalOutcomePort outcomes) {
        this.outcomes = outcomes;
    }

    @PostMapping("/preflight")
    public ApiResponse<Boolean> preflight(
            @RequestHeader("X-DWP-Service-Identity") String serviceIdentity,
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @Valid @RequestBody OwnerOutcomeRequest request) {
        requirePeopleService(serviceIdentity);
        outcomes.validateNewExecution(
                tenantId, actorId, MailProposalOutcomePort.Owner.HR,
                request.binding(),
                new MailProposalOutcomePort.OwnerMutation(null, request.ownerPayload()));
        return ApiResponse.success(true);
    }

    @PostMapping("/status")
    public ApiResponse<MailDtos.ProposalHandoff> status(
            @RequestHeader("X-DWP-Service-Identity") String serviceIdentity,
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @Valid @RequestBody OwnerBindingRequest request) {
        requirePeopleService(serviceIdentity);
        return ApiResponse.success(outcomes.status(
                tenantId, actorId, MailProposalOutcomePort.Owner.HR,
                request.binding()));
    }

    @PostMapping("/not-executed")
    public ApiResponse<MailDtos.ProposalHandoff> notExecuted(
            @RequestHeader("X-DWP-Service-Identity") String serviceIdentity,
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false)
            String correlationId,
            @Valid @RequestBody OwnerExecutionReleaseRequest request) {
        requirePeopleService(serviceIdentity);
        return ApiResponse.success(outcomes.notExecuted(
                tenantId, actorId, MailProposalOutcomePort.Owner.HR,
                request.binding(), request.reasonCode(), correlationId));
    }

    @PostMapping
    public ApiResponse<MailDtos.ProposalHandoff> record(
            @RequestHeader("X-DWP-Service-Identity") String serviceIdentity,
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false)
            String correlationId,
            @Valid @RequestBody OwnerOutcomeRequest request) {
        requirePeopleService(serviceIdentity);
        return ApiResponse.success(outcomes.executed(
                tenantId, actorId, MailProposalOutcomePort.Owner.HR,
                request.binding(), request.resultRef(), correlationId));
    }

    private void requirePeopleService(String serviceIdentity) {
        if (!PEOPLE_SERVICE_IDENTITY.equals(serviceIdentity)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The People service identity is required for an HR owner outcome.");
        }
    }

    public record OwnerOutcomeRequest(
            @NotNull UUID proposalId,
            @NotNull UUID commandId,
            @NotNull @Min(0) Long proposalVersion,
            @Size(max = 500) String resultRef,
            Map<String, Object> ownerPayload) {

        MailProposalHandoffBinding binding() {
            return new MailProposalHandoffBinding(
                    proposalId, commandId, proposalVersion);
        }
    }

    public record OwnerBindingRequest(
            @NotNull UUID proposalId,
            @NotNull UUID commandId,
            @NotNull @Min(0) Long proposalVersion) {

        MailProposalHandoffBinding binding() {
            return new MailProposalHandoffBinding(
                    proposalId, commandId, proposalVersion);
        }
    }

    public record OwnerExecutionReleaseRequest(
            @NotNull UUID proposalId,
            @NotNull UUID commandId,
            @NotNull @Min(0) Long proposalVersion,
            @NotBlank @Size(max = 80) String reasonCode) {

        MailProposalHandoffBinding binding() {
            return new MailProposalHandoffBinding(
                    proposalId, commandId, proposalVersion);
        }
    }
}
