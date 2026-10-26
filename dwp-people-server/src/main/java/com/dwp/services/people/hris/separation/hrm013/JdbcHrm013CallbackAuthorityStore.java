package com.dwp.services.people.hris.separation.hrm013;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackAuthorityKey;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackAuthorityRecord;

/** Append-only PostgreSQL adapter for the seven-column callback protocol fence. */
public final class JdbcHrm013CallbackAuthorityStore
        implements Hrm013PersistencePorts.CallbackAuthorityStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate required;

    public JdbcHrm013CallbackAuthorityStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mandatory = new TransactionTemplate(manager);
        this.mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.required = new TransactionTemplate(manager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public CallbackAuthorityRecord append(CallbackAuthorityRecord authority) {
        CallbackAuthorityRecord result = mandatory.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, authority.key().tenantId());
            int inserted = jdbc.update("""
                    INSERT INTO ppl_separation_callback_authorities(
                        public_id,tenant_id,case_public_id,task_public_id,
                        separation_case_id,separation_task_id,callback_type,
                        caller_workload_id,binding_id,audience,purpose_code,task_type,
                        approval_request_id,consumer_event_id,tenant_grant_revision,
                        authorization_revision,expected_next_owner_revision,
                        correlation_id,originating_payload_sha256,authority_revision,
                        decision,supersedes_authority_public_id,authority_digest,
                        created_at,closed_at)
                    VALUES(:publicId,:tenant,:casePublicId,:taskPublicId,:caseId,:taskId,
                        :callbackType,:caller,:binding,:audience,:purpose,:taskType,
                        :approvalRequest,:consumerEvent,:tenantGrantRevision,
                        :authorizationRevision,:ownerRevision,:correlation,:originDigest,
                        :authorityRevision,:decision,:supersedes,:authorityDigest,
                        :created,:closed)
                    """, parameters(authority));
            if (inserted != 1) {
                throw new IllegalStateException("HRM-013 callback authority insert failed");
            }
            return authority;
        });
        if (result == null) throw new IllegalStateException("HRM-013 authority transaction returned no result");
        return result;
    }

    @Override
    public Optional<CallbackAuthorityRecord> latest(CallbackAuthorityKey key) {
        Optional<CallbackAuthorityRecord> result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, key.tenantId());
            List<CallbackAuthorityRecord> rows = jdbc.query("""
                    SELECT public_id,tenant_id,case_public_id,task_public_id,
                           separation_case_id,separation_task_id,callback_type,
                           caller_workload_id,binding_id,audience,purpose_code,task_type,
                           approval_request_id,consumer_event_id,tenant_grant_revision,
                           authorization_revision,expected_next_owner_revision,
                           correlation_id,originating_payload_sha256,authority_revision,
                           decision,supersedes_authority_public_id,authority_digest,
                           created_at,closed_at
                      FROM ppl_separation_callback_authorities
                     WHERE tenant_id=:tenant
                       AND binding_id=:binding
                       AND caller_workload_id=:caller
                       AND case_public_id=:casePublicId
                       AND task_public_id IS NOT DISTINCT FROM :taskPublicId
                       AND purpose_code=:purpose
                       AND approval_request_id IS NOT DISTINCT FROM :approvalRequest
                     ORDER BY authority_revision DESC
                     LIMIT 1
                    """, keyParameters(key), (row, number) -> map(row));
            return rows.stream().findFirst();
        });
        return result == null ? Optional.empty() : result;
    }

    private static MapSqlParameterSource parameters(CallbackAuthorityRecord value) {
        CallbackAuthorityKey key = value.key();
        return keyParameters(key)
                .addValue("publicId", value.publicId())
                .addValue("caseId", value.separationCaseId())
                .addValue("taskId", value.separationTaskId() == 0
                        ? null : value.separationTaskId())
                .addValue("callbackType", value.callbackType())
                .addValue("audience", value.audience())
                .addValue("taskType", value.taskType())
                .addValue("consumerEvent", value.consumerEventId())
                .addValue("tenantGrantRevision", value.tenantGrantRevision())
                .addValue("authorizationRevision", value.authorizationRevision())
                .addValue("ownerRevision", value.expectedNextOwnerRevision())
                .addValue("correlation", value.correlationId())
                .addValue("originDigest", value.originatingPayloadSha256())
                .addValue("authorityRevision", value.authorityRevision())
                .addValue("decision", value.decision())
                .addValue("supersedes", value.supersedesAuthorityPublicId())
                .addValue("authorityDigest", value.authorityDigest())
                .addValue("created", Timestamp.from(value.createdAt()))
                .addValue("closed", value.closedAt() == null
                        ? null : Timestamp.from(value.closedAt()));
    }

    private static MapSqlParameterSource keyParameters(CallbackAuthorityKey key) {
        return new MapSqlParameterSource()
                .addValue("tenant", key.tenantId())
                .addValue("binding", key.bindingId())
                .addValue("caller", key.callerWorkloadId())
                .addValue("casePublicId", key.casePublicId())
                .addValue("taskPublicId", key.taskPublicId())
                .addValue("purpose", key.purposeCode())
                .addValue("approvalRequest", key.approvalRequestId());
    }

    private static CallbackAuthorityRecord map(ResultSet row) throws SQLException {
        UUID taskPublicId = row.getObject("task_public_id", UUID.class);
        UUID approvalRequestId = row.getObject("approval_request_id", UUID.class);
        Long taskId = row.getObject("separation_task_id", Long.class);
        Timestamp closedAt = row.getTimestamp("closed_at");
        return new CallbackAuthorityRecord(
                row.getObject("public_id", UUID.class),
                new CallbackAuthorityKey(
                        row.getLong("tenant_id"),
                        row.getString("binding_id"),
                        row.getString("caller_workload_id"),
                        row.getObject("case_public_id", UUID.class),
                        taskPublicId,
                        row.getString("purpose_code"),
                        approvalRequestId),
                row.getLong("separation_case_id"),
                taskId == null ? 0 : taskId,
                row.getString("callback_type"),
                row.getString("audience"),
                row.getString("task_type"),
                row.getObject("consumer_event_id", UUID.class),
                row.getLong("tenant_grant_revision"),
                row.getLong("authorization_revision"),
                row.getLong("expected_next_owner_revision"),
                row.getObject("correlation_id", UUID.class),
                row.getString("originating_payload_sha256"),
                row.getLong("authority_revision"),
                row.getString("decision"),
                row.getObject("supersedes_authority_public_id", UUID.class),
                row.getString("authority_digest"),
                row.getTimestamp("created_at").toInstant(),
                closedAt == null ? null : closedAt.toInstant());
    }
}
