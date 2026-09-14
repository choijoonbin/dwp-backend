package com.dwp.services.approval.documentretention.management.receipt;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Public reconciliation metadata excludes the private source authority and original body. */
@Schema(name="ApprovalRetentionCommandReceipt", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record ApprovalRetentionCommandReceipt(UUID commandId,
        @Schema(allowableValues={"INITIALIZE_POLICY","SAVE_POLICY","PUBLISH_POLICY","CLAIM_RECORD"}) String operation,
        String idempotencyKey, long actorUserId, String resourceSetKey,
        @Schema(nullable=true) UUID originalTargetId, UUID resultReferenceId, String requestBodySha256,
        @Schema(nullable=true) Long originalExpectedVersion, long resultVersion,
        @Schema(allowableValues="COMMITTED") String status, OffsetDateTime committedAt,
        @Schema(allowableValues={"POLICY_UPDATE_TRUSTED","POLICY_PUBLISH_SIGNED_HIGH_INDEPENDENT_CHECKER",
                "RETENTION_RECORD_EXECUTE_SIGNED_HIGH"}) String originAuthorityProfile,
        @Schema(allowableValues="RETENTION_COMMAND_RECEIPT_STEP_UP_TYPED_JSON_V1") String profileVersion) {}
