package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.AdminMailWritingAssetDtos.*;
import static com.dwp.services.platform.mail.MailWorkspaceDtos.BodyFormat.TEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMailWritingAssetServiceTest {

    @Mock
    private AdminMailWritingAssetRepository assets;
    @Mock
    private MailWorkspaceRepository workspace;
    @Mock
    private MailCommandRepository commands;
    @Mock
    private MailAdminMutationReceipts receipts;

    private AdminMailWritingAssetService service;

    @BeforeEach
    void setUp() {
        lenient().when(receipts.fingerprint(any(Object[].class))).thenReturn("fingerprint");
        lenient().when(receipts.claimOrReplay(
                        anyLong(), anyLong(), anyString(), any(UUID.class),
                        anyString(), anyString()))
                .thenReturn(null);
        service = new AdminMailWritingAssetService(assets, workspace, commands, receipts);
    }

    @Test
    void creatorCannotApproveTheirOwnOrganizationAsset() {
        UUID assetId = UUID.randomUUID();
        OrganizationAsset pending = asset(
                assetId, PublicationState.PENDING_APPROVAL, 7L, null, 2L);
        when(assets.asset(1L, AssetKind.TEMPLATE, assetId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.approve(
                1L, 7L, AssetKind.TEMPLATE, assetId, "corr",
                UUID.randomUUID(),
                new TransitionRequest(2L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mail administrator");

        verify(assets, never()).transition(
                eq(1L), eq(7L), eq(AssetKind.TEMPLATE), eq(assetId),
                eq(PublicationState.PENDING_APPROVAL), eq(PublicationState.APPROVED), eq(2L));
    }

    @Test
    void approvedVersionPublishesAndRetiresItsPriorLiveVersionAtomically() {
        UUID assetId = UUID.randomUUID();
        OrganizationAsset approved = asset(
                assetId, PublicationState.APPROVED, 7L, 9L, 3L);
        OrganizationAsset published = asset(
                assetId, PublicationState.PUBLISHED, 7L, 9L, 4L);
        when(assets.asset(1L, AssetKind.TEMPLATE, assetId)).thenReturn(Optional.of(approved));
        when(assets.transition(
                1L, 11L, AssetKind.TEMPLATE, assetId,
                PublicationState.APPROVED, PublicationState.PUBLISHED, 3L))
                .thenReturn(Optional.of(published));

        OrganizationAsset result = service.publish(
                1L, 11L, AssetKind.TEMPLATE, assetId, "corr-publish",
                UUID.randomUUID(),
                new TransitionRequest(3L));

        assertThat(result.publicationState()).isEqualTo(PublicationState.PUBLISHED);
        verify(assets).retirePublishedPredecessor(
                1L, 11L, AssetKind.TEMPLATE, approved);
        verify(commands).domainEvent(
                eq(1L), eq("MAIL_ORGANIZATION_ASSET"), eq(assetId),
                eq("mail.organization-asset.published"), anyMap(), eq("corr-publish"));
        verify(workspace).audit(
                eq(1L), eq(11L), eq("mail.organization-asset.published"),
                eq("MAIL_ORGANIZATION_ASSET"), eq(assetId.toString()),
                eq("corr-publish"), anyMap(), anyMap());
    }

    @Test
    void staleDraftUpdateFailsInsteadOfOverwritingAnotherVersion() {
        UUID assetId = UUID.randomUUID();
        DraftRequest request = new DraftRequest(
                "Company reply", "Subject", "Body", MailWorkspaceDtos.BodyFormat.TEXT,
                "Mandatory notice", false, false, null, 1L);
        when(assets.asset(1L, AssetKind.TEMPLATE, assetId)).thenReturn(Optional.of(
                asset(assetId, PublicationState.DRAFT, 7L, null, 2L)));
        when(assets.updateDraft(1L, 7L, AssetKind.TEMPLATE, assetId, request))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDraft(
                1L, 7L, AssetKind.TEMPLATE, assetId, "corr-update",
                UUID.randomUUID(), request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void organizationAssetRejectsUnsupportedVariablesBeforeItCanEnterGovernance() {
        DraftRequest request = new DraftRequest(
                "Unsafe template", "Hello {{recipientEmail}}", "Body", TEXT,
                "Mandatory notice", false, false, null, null);
        DraftRequest punctuationRequest = new DraftRequest(
                "Unsafe template", "Subject", "Body {{recipient-name}}", TEXT,
                "Mandatory notice", false, false, null, null);

        assertThatThrownBy(() -> service.createDraft(
                1L, 7L, AssetKind.TEMPLATE, "corr-variable",
                UUID.randomUUID(), request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Unsupported organization writing asset variable");
        assertThatThrownBy(() -> service.createDraft(
                1L, 7L, AssetKind.TEMPLATE, "corr-variable-punctuation",
                UUID.randomUUID(), punctuationRequest))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Unsupported organization writing asset variable");

        verifyNoInteractions(assets, workspace, commands);
    }

    private OrganizationAsset asset(
            UUID assetId,
            PublicationState state,
            long createdBy,
            Long approvedBy,
            long version) {
        OffsetDateTime now = OffsetDateTime.now();
        return new OrganizationAsset(
                assetId, AssetKind.TEMPLATE, UUID.randomUUID(), 1, state, null,
                "Company reply", "Subject", "Body", MailWorkspaceDtos.BodyFormat.TEXT,
                "Mandatory notice", false, false, createdBy, approvedBy,
                now, approvedBy == null ? null : now,
                state == PublicationState.PUBLISHED ? now : null,
                state == PublicationState.RETIRED ? now : null,
                version, now);
    }
}
