package com.dwp.services.provider;

import com.dwp.services.provider.operation.ProviderOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ProviderOperationsRepository {

    private final ProviderOperationsHealthRepository health;
    private final ProviderOperationsReliabilityRepository reliability;
    private final ProviderOperationsCommercialRepository commercial;
    private final ProviderOperationsApprovalRepository approvals;
    private final ProviderOperationsIncidentRepository incidents;
    private final ProviderOperationsSupportRepository support;

    public ProviderOperationsRepository(JdbcTemplate jdbc) {
        this.incidents = new ProviderOperationsIncidentRepository(jdbc);
        this.health = new ProviderOperationsHealthRepository(jdbc, incidents);
        this.reliability = new ProviderOperationsReliabilityRepository(jdbc);
        this.commercial = new ProviderOperationsCommercialRepository(jdbc);
        this.approvals = new ProviderOperationsApprovalRepository(jdbc);
        this.support = new ProviderOperationsSupportRepository(jdbc);
    }

    public ProviderDtos.CommandCenter commandCenter(ProviderDtos.EstateOverview estate) {
        return health.commandCenter(estate);
    }

    public ProviderDtos.ServiceHealthOverview serviceHealth() {
        return health.serviceHealth();
    }

    public ProviderDtos.ReliabilityControlOverview reliabilityControl() {
        return reliability.reliabilityControl();
    }

    public List<ProviderDtos.ServiceLevelObjectiveSummary> serviceLevelObjectives() {
        return reliability.serviceLevelObjectives();
    }

    public List<ProviderDtos.GovernanceDriftSummary> governanceDrift() {
        return reliability.governanceDrift();
    }

    public List<ProviderDtos.MaintenanceWindowSummary> maintenanceWindows() {
        return reliability.maintenanceWindows();
    }

    public UUID createMaintenanceWindow(
            ProviderDtos.CreateMaintenanceWindowRequest request,
            Long operatorId,
            UUID operationId) {
        return reliability.createMaintenanceWindow(request, operatorId, operationId);
    }

    public Optional<UUID> scheduleMaintenanceWindow(UUID operationId, Long operatorId) {
        return reliability.scheduleMaintenanceWindow(operationId, operatorId);
    }

    public void cancelMaintenanceWindow(UUID operationId, Long operatorId) {
        reliability.cancelMaintenanceWindow(operationId, operatorId);
    }

    public Optional<UUID> maintenanceWindowId(UUID operationId) {
        return reliability.maintenanceWindowId(operationId);
    }

    public ProviderDtos.CommercialOverview commercialOverview() {
        return commercial.commercialOverview();
    }

    public ProviderDtos.AuditInsights auditInsights() {
        return commercial.auditInsights();
    }

    public List<ProviderDtos.ServicePosture> servicePostures() {
        return health.servicePostures();
    }

    public List<ProviderDtos.CellPosture> cellPostures() {
        return health.cellPostures();
    }

    public List<ProviderDtos.OperationApprovalSummary> operationApprovals(String state) {
        return approvals.operationApprovals(state);
    }

    public void ensureOperationApproval(ProviderOperation operation) {
        approvals.ensureOperationApproval(operation);
    }

    public Optional<ApprovalRecord> approval(UUID approvalId) {
        return approvals.approval(approvalId);
    }

    public boolean operationApproved(UUID operationId) {
        return approvals.operationApproved(operationId);
    }

    public boolean decideApproval(
            UUID approvalId,
            String decision,
            String reason,
            Long operatorId,
            long version) {
        return approvals.decideApproval(approvalId, decision, reason, operatorId, version);
    }

    public List<ProviderDtos.ServiceIncidentSummary> incidents(int limit) {
        return incidents.incidents(limit);
    }

    public Optional<IncidentRecord> incident(UUID incidentId) {
        return incidents.incident(incidentId);
    }

    public UUID createIncident(
            ProviderDtos.CreateIncidentRequest request,
            Long operatorId,
            String correlationId) {
        return incidents.createIncident(request, operatorId, correlationId);
    }

    public boolean updateIncident(
            UUID incidentId,
            String state,
            String message,
            String visibility,
            Long operatorId,
            long version) {
        return incidents.updateIncident(
                incidentId, state, message, visibility, operatorId, version);
    }

    public SupportPolicy supportPolicy(Set<String> scopes) {
        return support.supportPolicy(scopes);
    }

    public List<ProviderDtos.SupportScopeSummary> supportScopes() {
        return support.supportScopes();
    }

    public record ApprovalRecord(
            UUID approvalId,
            UUID operationId,
            String lifecycleState,
            String requiredRoleCode,
            boolean separationOfDuties,
            Long requestedBy,
            Instant expiresAt,
            long version) {
    }

    public record IncidentRecord(UUID incidentId, String lifecycleState, long version) {
    }

    public record SupportPolicy(
            String riskTier,
            boolean requiresCustomerApproval,
            int matchedScopes) {
    }
}
