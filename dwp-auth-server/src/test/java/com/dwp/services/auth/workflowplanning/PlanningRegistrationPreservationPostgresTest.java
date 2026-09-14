package com.dwp.services.auth.workflowplanning;

import static org.assertj.core.api.Assertions.assertThat;
import com.dwp.services.auth.service.WorkflowRuntimeActualAuthHarness;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class PlanningRegistrationPreservationPostgresTest {
    @Test void registrationDoesNotReviveRetiredDisabledMetadataOrCreateAuthority() throws Exception {
        try(var auth=new WorkflowRuntimeActualAuthHarness(PlanningProofTestFixture.key("planning-preservation-owner"),
                PlanningProofTestFixture.key("planning-preservation-transport"),PlanningProofTestFixture.key("planning-preservation-auth"))) {
            var jdbc=auth.jdbc();long tenant=auth.tenantId();var before=new LinkedHashMap<String,Long>();
            for(String table:new String[]{"sys_tenant_role_permission_templates","com_admin_scoped_duty_assignments","com_admin_role_assignments","com_principal_resource_grants","auth_product_authorization_active"})
                before.put(table,jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class));
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status->{
                jdbc.update("UPDATE com_resources SET enabled=false WHERE tenant_id=? AND key='ADMIN.APPROVAL_WORKFLOW'",tenant);
                jdbc.update("UPDATE sys_tenant_resource_templates SET lifecycle_state='RETIRED' WHERE resource_key='ADMIN.APPROVAL_WORKFLOW'");
                jdbc.update("UPDATE sys_admin_scoped_duty_catalog SET lifecycle_state='RETIRED' WHERE duty_code IN('APPROVAL_WORKFLOW_PLANNING','APPROVAL_FORM_REFERENCE_READ')");
                jdbc.execute((ConnectionCallback<Void>)connection->{ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V214__register_workflow_planning_authority_sources.sql"));return null;});
                assertThat(jdbc.queryForObject("SELECT enabled FROM com_resources WHERE tenant_id=? AND key='ADMIN.APPROVAL_WORKFLOW'",Boolean.class,tenant)).isFalse();
                assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM sys_tenant_resource_templates WHERE resource_key='ADMIN.APPROVAL_WORKFLOW'",String.class)).isEqualTo("RETIRED");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_admin_scoped_duty_catalog WHERE duty_code IN('APPROVAL_WORKFLOW_PLANNING','APPROVAL_FORM_REFERENCE_READ') AND lifecycle_state='RETIRED'",Long.class)).isEqualTo(2);
                for(var entry:before.entrySet()) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+entry.getKey(),Long.class)).as(entry.getKey()).isEqualTo(entry.getValue());
                status.setRollbackOnly();return null;
            });
        }
    }
}
