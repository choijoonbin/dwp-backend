package com.dwp.services.synapsex.service.workbench;

import com.dwp.services.synapsex.event.ActionCompletedEvent;
import com.dwp.services.synapsex.service.dashboard.DashboardQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * DB 커밋 직후 조치 완료 신호 발행 — Redis + 선택적 Aura 웹훅.
 * Backend Single Source of Truth: DB 갱신은 이미 완료된 뒤 실행됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionCompletionListener {

    private final WorkbenchActionCompletionPublisher actionCompletionPublisher;
    private final DashboardQueryService dashboardQueryService;
    private final AuraActionCompletedWebhookNotifier auraWebhookNotifier;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActionCompleted(ActionCompletedEvent event) {
        Long tenantId = event.getTenantId();
        Object newKpi = null;
        try {
            newKpi = dashboardQueryService.getSynapseDashboardSummary(tenantId);
        } catch (Exception e) {
            log.debug("KPI summary for event skipped: {}", e.getMessage());
        }
        actionCompletionPublisher.publishActionCompleted(
                event.getTenantId(), event.getCaseId(), event.getActionId(), event.getActionType(),
                event.getCaseIdString(), event.getRequestId(), event.getExecutorId(), event.isApproved(),
                event.getHistoryId(), event.getFiDocUpdated(), newKpi);
        auraWebhookNotifier.notifyIfConfigured(event, newKpi);
    }
}
