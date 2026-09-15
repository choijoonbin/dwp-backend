package com.dwp.services.approval.domain;

import org.mockito.Mockito;

public final class ApprovalWorkflowCommandFenceTestSupport {
    @SuppressWarnings("try")
    public static void runWithoutLiveDatabase(Runnable action) {
        try (var ignored = Mockito.mockStatic(ApprovalWorkflowCommandLiveFence.class)) {
            action.run();
        }
    }

    private ApprovalWorkflowCommandFenceTestSupport() { }
}
