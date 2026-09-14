package com.dwp.services.auth.workflowplanning;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import com.dwp.services.auth.service.WorkflowRuntimeActualAuthHarness;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;

class PlanningFreshCatalogPostgresTest {
    @Test
    void freshFullAuthProvidesExactPlanningResourcesAndScopedBindings() throws Exception {
        try(var auth=new WorkflowRuntimeActualAuthHarness(new RSAKeyGenerator(2048).keyID("planning-catalog-unrelated-owner").generate(),
                new RSAKeyGenerator(2048).keyID("planning-catalog-unrelated-transport").generate(),
                new RSAKeyGenerator(2048).keyID("planning-catalog-unrelated-auth").generate())) {
            var jdbc=auth.jdbc(); long tenant=auth.tenantId();
            assertSoftly(check->{
                for(var entry:PlanningProtocol.REQUIRED.entrySet()) {
                    int split=entry.getValue().lastIndexOf(':'); String resource=entry.getValue().substring(0,split),permission=entry.getValue().substring(split+1);
                    check.assertThat(jdbc.queryForObject("SELECT count(*) FROM com_resources WHERE tenant_id=? AND key=? AND enabled",Long.class,tenant,resource))
                            .as("exact registered fresh resource %s",resource).isEqualTo(1);
                    check.assertThat(jdbc.queryForObject("""
                            SELECT count(*) FROM sys_admin_scoped_duty_capabilities capability
                            JOIN sys_admin_scoped_duty_catalog duty USING(duty_code)
                            WHERE capability.capability_contract_key=? AND capability.permission_resource_key=?
                              AND capability.permission_code=? AND duty.resource_key=? AND duty.product_key='approvals'
                              AND duty.product_resource_key='APP.APPROVALS' AND NOT duty.audit_policy_exception
                            """,Long.class,entry.getKey(),resource,permission,resource))
                            .as("exact current scoped mapping %s -> %s",entry.getKey(),entry.getValue()).isEqualTo(1);
                }
            });
        }
    }
}
