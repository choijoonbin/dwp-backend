package com.dwp.services.approval.informationreplay;

import com.dwp.services.approval.domain.ApprovalWorkflowQuorumInformationRuntime;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** Public receipt only; admission credentials and internal actor identifiers are never returned. */
@Schema(name="ApprovalInformationCommandReceipt",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record InformationCommandReceipt(
        @NotNull @Pattern(regexp="COMPLETED") @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues="COMPLETED") String status,
        @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID roundId,
        @Min(1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1") long generation,
        @Min(1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1") long requestVersion,
        @Min(1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1") int payloadRevision,
        @NotNull @Pattern(regexp="[a-f0-9]{64}") @Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="[a-f0-9]{64}") String payloadSha256,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean materialChange) {
    static InformationCommandReceipt from(ApprovalWorkflowQuorumInformationRuntime.Receipt receipt) {
        if(receipt==null || !"COMPLETED".equals(receipt.status()) || receipt.roundId()==null || receipt.generation()<1
                || receipt.requestVersion()<1 || receipt.payloadRevision()<1 || receipt.payloadSha256()==null
                || !receipt.payloadSha256().matches("[a-f0-9]{64}")) throw com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied();
        return new InformationCommandReceipt(receipt.status(),receipt.roundId(),receipt.generation(),receipt.requestVersion(),
                receipt.payloadRevision(),receipt.payloadSha256(),receipt.materialChange());
    }
}
