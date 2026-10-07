package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MailWorkspaceDtosBinaryCompatibilityTest {

    private static final Set<String> PUBLISHED_NESTED_TYPES = Set.of(
            "Recipient",
            "RecipientType",
            "BodyFormat",
            "AssetScope",
            "Attachment",
            "ComposeCapabilities",
            "ComposeContext",
            "ComposeOptions",
            "AdvancedComposeRequest",
            "AdvancedComposeResult",
            "SearchCriteria",
            "SavedView",
            "SavedViewRequest",
            "FollowUp",
            "FollowUpRequest",
            "Template",
            "TemplateRequest",
            "Signature",
            "SignatureRequest",
            "WritingAssets",
            "Preferences",
            "PreferencesRequest",
            "DeliverySummary",
            "DeliveryPage",
            "DeliveryTimeline",
            "DeliveryReceipt",
            "RescheduleRequest",
            "VersionRequest",
            "RuleOrderItem",
            "RuleOrderRequest",
            "LifecyclePreview",
            "ProposalUpdateRequest",
            "GroupSendReceipt",
            "AdminSourceEvidence",
            "AdminException",
            "AdminCommandAudit",
            "AdminOperationsSnapshot",
            "ConnectionOperationRequest",
            "ConnectionOperation",
            "AccessPermissions",
            "SharedInboxAccessMember",
            "SharedInboxAccess",
            "SharedInboxMemberCandidate",
            "AccessImpact",
            "SharedInboxMemberRequest",
            "SharedInboxMemberRevokeRequest",
            "SharedInboxMemberRevokePreviewRequest",
            "SharedInboxMemberRevokePreview",
            "PolicyEvidenceRow",
            "PolicyHistory",
            "PolicyApprovalEvidence",
            "PolicyRecoveryEvidence",
            "PolicyGovernance",
            "ResourceRetentionPolicy",
            "LegalHold",
            "LegalHoldRequest",
            "LegalHoldReleasePreviewRequest",
            "LegalHoldReleaseImpact",
            "LegalHoldReleaseApproval",
            "LegalHoldReleasePreview",
            "LegalHoldReleaseApprovalRequest",
            "LegalHoldReleaseExecuteRequest",
            "LegalHoldReleaseExecution",
            "PurgeJob",
            "RetentionSnapshot",
            "PurgePreviewRequest",
            "PurgePreview",
            "PurgeApprovalRequest",
            "PurgeApproval",
            "PurgeExecuteRequest",
            "DeliveryAuditTimeline",
            "DeliveryAuditItem",
            "DeliveryAuditPage",
            "DeliveryRecoveryRequest",
            "DeliveryExportRequest",
            "EvidenceExportApprovalRequest",
            "EvidenceExportApproval",
            "DeliveryExport",
            "RetentionEvidenceExportRequest",
            "RetentionEvidenceExport");

    @Test
    void preservesPublishedNestedContractBinaryNames() throws Exception {
        Set<String> publicNestedTypes = Arrays.stream(MailWorkspaceDtos.class.getDeclaredClasses())
                .filter(type -> Modifier.isPublic(type.getModifiers()))
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(publicNestedTypes).containsAll(PUBLISHED_NESTED_TYPES);
        for (String simpleName : PUBLISHED_NESTED_TYPES) {
            assertThat(Class.forName(MailWorkspaceDtos.class.getName() + "$" + simpleName))
                    .isNotNull();
        }
    }

    @Test
    void preservesPublishedAttachmentDownloadBinaryName() throws Exception {
        assertThat(Class.forName(MailWorkspaceService.class.getName() + "$AttachmentDownload"))
                .isNotNull();
    }

    @Test
    void preservesComposeContextRecordAndCanonicalDescriptor() throws Exception {
        Class<MailWorkspaceDtos.ComposeContext> type = MailWorkspaceDtos.ComposeContext.class;

        assertThat(type.isRecord()).isTrue();
        assertThat(type.getDeclaredConstructor(
                List.class,
                MailWorkspaceDtos.ComposeCapabilities.class,
                Map.class,
                Map.class,
                List.class,
                List.class,
                MailWorkspaceDtos.Preferences.class,
                Map.class,
                OffsetDateTime.class)).isNotNull();
        assertThat(type.getDeclaredMethod("accounts").getGenericReturnType().getTypeName())
                .contains("MailDtos$AccountSummary");
        assertThat(type.getDeclaredMethod("accountReadiness").getGenericReturnType().getTypeName())
                .contains("MailDtos$AccountReadiness");

        OffsetDateTime generatedAt = OffsetDateTime.parse("2026-10-07T00:00:00Z");
        MailWorkspaceDtos.ComposeContext first = new MailWorkspaceDtos.ComposeContext(
                List.of(), null, Map.of(), Map.of(), List.of(), List.of(), null, Map.of(),
                generatedAt);
        MailWorkspaceDtos.ComposeContext second = new MailWorkspaceDtos.ComposeContext(
                List.of(), null, Map.of(), Map.of(), List.of(), List.of(), null, Map.of(),
                generatedAt);
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(first);
        assertThat(json.has("accounts")).isTrue();
        assertThat(json.has("accountReadiness")).isTrue();
        assertThat(json.has("generatedAt")).isTrue();
    }

    @Test
    void preservesAdvancedComposeResultRecordAndCanonicalDescriptor() throws Exception {
        Class<MailWorkspaceDtos.AdvancedComposeResult> type =
                MailWorkspaceDtos.AdvancedComposeResult.class;

        assertThat(type.isRecord()).isTrue();
        assertThat(type.getDeclaredConstructor(
                MailDtos.ThreadDetail.class,
                MailWorkspaceDtos.DeliveryReceipt.class)).isNotNull();
        assertThat(type.getDeclaredMethod("thread").getReturnType())
                .isEqualTo(MailDtos.ThreadDetail.class);

        JsonNode json = new ObjectMapper().valueToTree(
                new MailWorkspaceDtos.AdvancedComposeResult(null, null));
        assertThat(json.has("thread")).isTrue();
        assertThat(json.has("receipt")).isTrue();
    }
}
