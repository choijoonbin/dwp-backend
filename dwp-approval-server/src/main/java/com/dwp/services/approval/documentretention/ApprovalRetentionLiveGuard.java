package com.dwp.services.approval.documentretention;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Request locks outlive all body projection; retention uses the same request-first lock order. */
public final class ApprovalRetentionLiveGuard {
    public static final String LIVE="NOT EXISTS(SELECT 1 FROM apr_record_retention_heads retention WHERE retention.tenant_id=request.tenant_id AND retention.request_id=request.request_id AND retention.state<>'LIVE')";
    private final NamedParameterJdbcTemplate jdbc;
    public ApprovalRetentionLiveGuard(NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}
    public List<UUID> lockQuery(long tenant,String ownerSelect,MapSqlParameterSource parameters) {
        transaction();
        var ids=jdbc.query("SELECT request.request_id FROM apr_requests request WHERE request.tenant_id=:tenantId AND EXISTS(SELECT 1 FROM ("+ownerSelect+") selected WHERE selected.request_id=request.request_id) ORDER BY request.request_id FOR SHARE OF request",
                parameters,(r,n)->r.getObject(1,UUID.class));
        var current=live(tenant,ids);
        parameters.addValue("retentionRequests",current.isEmpty()?List.of(new UUID(0,0)):current);
        return current;
    }
    public void request(long tenant,UUID request) {
        request(tenant,request,"FOR SHARE");
    }
    public void writeRequest(long tenant,UUID request) {
        request(tenant,request,"FOR UPDATE");
    }
    private void request(long tenant,UUID request,String lock) {
        transaction();
        var ids=jdbc.query("SELECT request_id FROM apr_requests WHERE tenant_id=:tenant AND request_id=:request "+lock,
                Map.of("tenant",tenant,"request",request),(r,n)->r.getObject(1,UUID.class));
        if(ids.size()!=1 || live(tenant,ids).isEmpty()) throw hidden();
    }
    public void task(long tenant,UUID task) {
        transaction();
        var ids=jdbc.query("SELECT request.request_id FROM apr_requests request JOIN apr_tasks task USING(tenant_id,request_id) WHERE task.tenant_id=:tenant AND task.task_id=:task ORDER BY request.request_id FOR SHARE OF request",
                Map.of("tenant",tenant,"task",task),(r,n)->r.getObject(1,UUID.class));
        if(ids.size()!=1 || live(tenant,ids).isEmpty()) throw hidden();
    }
    private List<UUID> live(long tenant,List<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        jdbc.query("SELECT request_id FROM apr_record_retention_heads WHERE tenant_id=:tenant AND request_id IN(:ids) ORDER BY request_id FOR SHARE",
                Map.of("tenant",tenant,"ids",ids),(r,n)->r.getObject(1,UUID.class));
        return jdbc.query("SELECT request.request_id FROM apr_requests request WHERE request.tenant_id=:tenant AND request.request_id IN(:ids) AND "+LIVE+" ORDER BY request.request_id",
                Map.of("tenant",tenant,"ids",ids),(r,n)->r.getObject(1,UUID.class));
    }
    private void transaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
        var source=jdbc.getJdbcTemplate().getDataSource();
        if(source==null || !TransactionSynchronizationManager.hasResource(source)) throw unavailable();
        var connection=DataSourceUtils.getConnection(source);
        try {
            if(!DataSourceUtils.isConnectionTransactional(connection,source) || connection.getAutoCommit()
                    || connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED) throw unavailable();
        } catch(SQLException failure) {throw unavailable();}
        finally {DataSourceUtils.releaseConnection(connection,source);}
    }
    private static BaseException hidden(){return new BaseException(ErrorCode.NOT_FOUND,"Approval resource is unavailable.");}
    private static BaseException unavailable(){return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Approval read requires a current retention-fenced transaction.");}
}
