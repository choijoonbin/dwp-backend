package com.dwp.services.platform.savedview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewControllerTest {

    @Mock private SavedViewService service;

    private SavedViewController controller;

    @BeforeEach
    void setUp() {
        controller = new SavedViewController(service);
    }

    @Test
    void forwardsVerifiedPermissionsForSurfaceScopedReads() {
        when(service.list(
                3L, 17L, "APP.WORK:VIEW", "WORKSPACE_MEMBER", null,
                "workspace.work")).thenReturn(List.of());

        controller.list(
                3L, 17L, "APP.WORK:VIEW", "WORKSPACE_MEMBER", null,
                "workspace.work");

        verify(service).list(
                3L, 17L, "APP.WORK:VIEW", "WORKSPACE_MEMBER", null,
                "workspace.work");
    }

    @Test
    void forwardsVerifiedPermissionsForIdScopedUseMutations() {
        UUID savedViewId = UUID.randomUUID();

        controller.markUsed(3L, 17L, "APP.APPS:VIEW", null, savedViewId);

        verify(service).markUsed(
                3L, 17L, "APP.APPS:VIEW", null, savedViewId);
    }
}
