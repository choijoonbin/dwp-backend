package com.dwp.services.approval.hrisbridge.separation;

import static com.dwp.services.approval.hrisbridge.separation.HrisSeparationPersistenceModels.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Append-only tenant grant ledger and current-authority revalidation. */
public final class HrisSeparationGrantStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final HrisSeparationRlsScope scope;
    private final TransactionTemplate transactions;

    public HrisSeparationGrantStore(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.scope = new HrisSeparationRlsScope(jdbc);
        this.transactions = new TransactionTemplate(manager);
    }

    public TenantGrant append(GrantWrite grant) {
        return required(transactions.execute(status -> {
            scope.bind(grant.tenantId());
            String digest = grantDigest(grant);
            int inserted = jdbc.update("""
                    INSERT INTO apr_hrm013_tenant_grants(
                        grant_id,tenant_id,grant_revision,tenant_grant_revision,
                        authorization_revision,decision,people_spiffe_id,
                        people_signing_key_id,approval_spiffe_id,approval_signing_key_id,
                        request_binding_id,request_purpose_code,callback_binding_id,
                        callback_purpose_code,supersedes_grant_id,grant_digest,recorded_at)
                    VALUES(:grant,:tenant,:revision,:tenantRevision,:authorizationRevision,
                        :decision,:peopleSpiffe,:peopleKey,:approvalSpiffe,:approvalKey,
                        :requestBinding,:requestPurpose,:callbackBinding,:callbackPurpose,
                        :supersedes,:digest,:recorded)
                    """, new MapSqlParameterSource()
                    .addValue("grant", grant.grantId()).addValue("tenant", grant.tenantId())
                    .addValue("revision", grant.grantRevision())
                    .addValue("tenantRevision", grant.tenantGrantRevision())
                    .addValue("authorizationRevision", grant.authorizationRevision())
                    .addValue("decision", grant.decision().name())
                    .addValue("peopleSpiffe", PEOPLE_SPIFFE)
                    .addValue("peopleKey", grant.peopleSigningKeyId())
                    .addValue("approvalSpiffe", APPROVAL_SPIFFE)
                    .addValue("approvalKey", grant.approvalSigningKeyId())
                    .addValue("requestBinding", REQUEST_BINDING)
                    .addValue("requestPurpose", REQUEST_PURPOSE)
                    .addValue("callbackBinding", CALLBACK_BINDING)
                    .addValue("callbackPurpose", CALLBACK_PURPOSE)
                    .addValue("supersedes", grant.supersedesGrantId())
                    .addValue("digest", digest)
                    .addValue("recorded", Timestamp.from(grant.recordedAt())));
            if (inserted != 1) throw new IllegalStateException("HRM-013 grant insert failed.");
            return currentInScope(grant.tenantId());
        }));
    }

    public TenantGrant current(long tenantId) {
        return required(transactions.execute(status -> {
            scope.bind(tenantId);
            return currentInScope(tenantId);
        }));
    }

    public TenantGrant requireCurrentRequestGrant(long tenantId, UUID grantId,
            long tenantGrantRevision, long authorizationRevision,
            String peopleSigningKeyId) {
        return required(transactions.execute(status -> {
            scope.bind(tenantId);
            TenantGrant grant = currentInScope(tenantId);
            requireAdmit(grant, grantId, tenantGrantRevision, authorizationRevision);
            if (!grant.peopleSigningKeyId().equals(peopleSigningKeyId)) {
                throw new IllegalStateException("HRM-013 People signing authority is stale.");
            }
            return grant;
        }));
    }

    public TenantGrant requireCurrentCallbackGrant(long tenantId, UUID grantId,
            long tenantGrantRevision, long authorizationRevision,
            String approvalSigningKeyId) {
        return required(transactions.execute(status -> {
            scope.bind(tenantId);
            TenantGrant grant = currentInScope(tenantId);
            requireAdmit(grant, grantId, tenantGrantRevision, authorizationRevision);
            if (!grant.approvalSigningKeyId().equals(approvalSigningKeyId)) {
                throw new IllegalStateException("HRM-013 Approval signing authority is stale.");
            }
            return grant;
        }));
    }

    TenantGrant requireCurrentAdmitInScope(long tenantId, UUID grantId,
            long tenantGrantRevision, long authorizationRevision) {
        TenantGrant grant = currentInScope(tenantId);
        requireAdmit(grant, grantId, tenantGrantRevision, authorizationRevision);
        return grant;
    }

    void lockTenantInScope(long tenantId) {
        jdbc.queryForObject("""
                SELECT pg_advisory_xact_lock(hashtextextended(:material,0))
                """, new MapSqlParameterSource("material",
                "DWP_APPROVAL_HRM013_GRANT_V1:" + tenantId), Object.class);
    }

    private TenantGrant currentInScope(long tenantId) {
        List<TenantGrant> grants = jdbc.query("""
                SELECT grant_id,tenant_id,grant_revision,tenant_grant_revision,
                       authorization_revision,decision,people_signing_key_id,
                       approval_signing_key_id,supersedes_grant_id,grant_digest,recorded_at
                  FROM apr_hrm013_tenant_grants
                 WHERE tenant_id=:tenant
                 ORDER BY grant_revision DESC LIMIT 1
                """, new MapSqlParameterSource("tenant", tenantId), (row, number) ->
                new TenantGrant(row.getObject("grant_id", UUID.class),
                        row.getLong("tenant_id"), row.getLong("grant_revision"),
                        row.getLong("tenant_grant_revision"),
                        row.getLong("authorization_revision"),
                        GrantDecision.valueOf(row.getString("decision")),
                        row.getString("people_signing_key_id"),
                        row.getString("approval_signing_key_id"),
                        row.getObject("supersedes_grant_id", UUID.class),
                        row.getString("grant_digest"),
                        row.getTimestamp("recorded_at").toInstant()));
        if (grants.size() != 1) {
            throw new IllegalStateException("Current HRM-013 tenant grant is unavailable.");
        }
        return grants.getFirst();
    }

    private static void requireAdmit(TenantGrant grant, UUID grantId,
            long tenantGrantRevision, long authorizationRevision) {
        if (grant.decision() != GrantDecision.ADMIT
                || !grant.grantId().equals(grantId)
                || grant.tenantGrantRevision() != tenantGrantRevision
                || grant.authorizationRevision() != authorizationRevision) {
            throw new IllegalStateException("Current HRM-013 tenant grant does not admit the operation.");
        }
    }

    private static <T> T required(T value) {
        if (value == null) throw new IllegalStateException("HRM-013 transaction returned no value.");
        return value;
    }
}
