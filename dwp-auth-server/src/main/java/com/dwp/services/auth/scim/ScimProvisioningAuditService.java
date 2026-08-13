package com.dwp.services.auth.scim;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ScimProvisioningAuditService {

    private final JdbcTemplate jdbc;

    public ScimProvisioningAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void success(
            String operation,
            String resourceType,
            String resourceId,
            String externalId,
            String correlationId) {
        ScimConnectorContext.ConnectorIdentity identity = ScimConnectorContext.require();
        jdbc.update("""
                INSERT INTO sys_scim_provisioning_events (
                    scim_event_id, tenant_id, scim_connector_id, operation,
                    resource_type, resource_id, external_id, outcome, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'SUCCESS', ?)
                """,
                UUID.randomUUID(), identity.tenantId(), identity.connectorId(), operation,
                resourceType, resourceId, externalId, correlationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(
            String operation,
            String resourceType,
            String resourceId,
            String externalId,
            String correlationId,
            String reason) {
        ScimConnectorContext.ConnectorIdentity identity = ScimConnectorContext.require();
        jdbc.update("""
                INSERT INTO sys_scim_provisioning_events (
                    scim_event_id, tenant_id, scim_connector_id, operation,
                    resource_type, resource_id, external_id, outcome,
                    correlation_id, redacted_summary)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DENIED', ?,
                    jsonb_build_object('reason', ?))
                """,
                UUID.randomUUID(), identity.tenantId(), identity.connectorId(), operation,
                resourceType, resourceId, externalId, correlationId, reason);
    }
}
