package com.dwp.services.auth.workflowplanning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.dwp.core.exception.BaseException;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

class PlanningReadinessTest {
    @Test void disabledDefaultIsHealthyWithoutResolvingKeysRegistryOrRedis() {
        var service=mock(PlanningAuthorityService.class);
        when(service.enabled()).thenReturn(false);
        var health=new PlanningReadiness(service).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state","DISABLED");
        verify(service,never()).requireReady();
    }
    @Test void enabledReadinessRequiresAllPrivateAuthorityDependencies() {
        var service=mock(PlanningAuthorityService.class);
        when(service.enabled()).thenReturn(true);
        var ready=new PlanningReadiness(service).health();
        assertThat(ready.getStatus()).isEqualTo(Status.UP);
        assertThat(ready.getDetails()).containsEntry("state","READY");
        verify(service).requireReady();
        doThrow(PlanningProtocol.unavailable()).when(service).requireReady();
        var unavailable=new PlanningReadiness(service).health();
        assertThat(unavailable.getStatus()).isEqualTo(Status.DOWN);
        assertThat(unavailable.getDetails()).containsEntry("state","UNAVAILABLE");
    }
    @Test void supportedPolicyRevisionIsExactAndBoundedToInstalledPlanningContracts() {
        for(long version=10;version<=14;version++)
            assertThat(PlanningProtocol.supportedPolicyRevision("policy-"+version+"-1-"+"a".repeat(64))).isTrue();
        for(String invalid:java.util.List.of("policy-9-1-"+"a".repeat(64),"policy-15-1-"+"a".repeat(64),
                "policy-14-0-"+"a".repeat(64),"policy-14-1-"+"A".repeat(64),"policy-14-1-"+"a".repeat(63),""))
            assertThat(PlanningProtocol.supportedPolicyRevision(invalid)).as(invalid).isFalse();
        assertThat(PlanningProtocol.supportedPolicyRevision(null)).isFalse();
    }
    @Test void authKeyConfigurationRejectsApprovalPrivateMaterialInTheSameServiceEnvironment() {
        var fixture=new PlanningProofTestFixture();String prefix="dwp.auth.approval-workflow-planning.";
        var environment=new MockEnvironment()
                .withProperty(prefix+"owner-public-jwks",new JWKSet(fixture.owner.toPublicJWK()).toString())
                .withProperty(prefix+"transport-public-jwks",new JWKSet(fixture.transport.toPublicJWK()).toString())
                .withProperty(prefix+"attestation-private-jwk",fixture.attestation.toJSONString())
                .withProperty(prefix+"attestation-public-jwks",new JWKSet(fixture.attestation.toPublicJWK()).toString())
                .withProperty("dwp.approval.workflow-planning.owner-private-key",fixture.owner.toJSONString());
        assertThatThrownBy(()->new PlanningConfiguration().planningKeys(new PlanningJson(),environment))
                .isInstanceOf(BaseException.class);
    }
}
