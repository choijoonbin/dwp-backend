package com.dwp.services.auth.service;

import com.dwp.platform.contracts.hris.identity.v1.AuthPersonBindingV1;
import com.dwp.platform.contracts.hris.identity.v2.*;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual owner hash/factory composition; underlying AuthService/governance/duty APIs are
 * EXPLICIT MOCKS. No native permission/JPA/Gateway transport or whole PEP claim. */
class NativeHrisCurrentRoleEvidenceReaderV2Test {
    enum Missing { SOURCE, TRUTH, CLOCK, LEASE }
    @ParameterizedTest @EnumSource(Missing.class)
    void missingTrustedAdapterMakesZeroClockTruthAndSqlCalls(Missing missing) throws Exception {
        var source=mock(DataSource.class);var truth=mock(NativeHrisAuthIdentityEvidenceTruthV2.Provider.class);var clock=mock(Clock.class);
        var reader=new NativeHrisCurrentRoleEvidenceReaderV2(missing==Missing.SOURCE?null:source,
                missing==Missing.TRUTH?null:truth,missing==Missing.CLOCK?null:clock,missing==Missing.LEASE?null:Duration.ofSeconds(10));
        denied(()->reader.loadCurrent(null),NativeHrisCurrentRoleEvidenceReaderV2.Code.UNAVAILABLE);
        verifyNoInteractions(source,truth,clock);
    }
    enum Invalid { NULL_LOOKUP, ZERO_LEASE, NEGATIVE_LEASE, LONG_LEASE, ZERO_PRINCIPAL, USER_VERSION, ACCESS_VERSION, EXPIRED }
    @ParameterizedTest @EnumSource(Invalid.class)
    void malformedOrStalePrivateLookupNeverStartsNativeRead(Invalid invalid) throws Exception {
        Fixture f=new Fixture();var source=mock(DataSource.class);var truth=mock(NativeHrisAuthIdentityEvidenceTruthV2.Provider.class);
        if(invalid==Invalid.ZERO_PRINCIPAL)f.principal=new UUID(0,0);
        if(invalid==Invalid.USER_VERSION)f.version=-1;if(invalid==Invalid.ACCESS_VERSION)f.access=-1;
        if(invalid==Invalid.ZERO_PRINCIPAL||invalid==Invalid.USER_VERSION||invalid==Invalid.ACCESS_VERSION) {
            assertThrows(CurrentHrisAuthorizationExceptionV2.class,f::lookup);verifyNoInteractions(source,truth);return;
        }
        Duration lease=switch(invalid){case ZERO_LEASE->Duration.ZERO;case NEGATIVE_LEASE->Duration.ofSeconds(-1);case LONG_LEASE->Duration.ofSeconds(31);default->Duration.ofSeconds(10);};
        var clock=Clock.fixed(invalid==Invalid.EXPIRED?f.now.plusSeconds(20):f.now,ZoneOffset.UTC);
        denied(()->new NativeHrisCurrentRoleEvidenceReaderV2(source,truth,clock,lease).loadCurrent(invalid==Invalid.NULL_LOOKUP?null:f.lookup()),
                invalid==Invalid.EXPIRED?NativeHrisCurrentRoleEvidenceReaderV2.Code.STALE:NativeHrisCurrentRoleEvidenceReaderV2.Code.INVALID);
        verifyNoInteractions(source,truth);
    }
    @Test void factoryReusesActualNativeIdentityOwnerAlgorithmAndNeverItsOwnHash() {
        Fixture f=new Fixture();var direct=new ProductAuthorizationIdentityEvidenceService(f.auth,f.governance,f.duties).load(f.tenant,f.user);
        var actual=f.truth().loadCurrent(f.tenant,f.user);
        assertEquals(direct.revision(),actual.authRevision());assertEquals(direct.roles(),actual.roles());
        assertTrue(actual.authRevision().matches("auth-[0-9a-f]{64}"));
        when(f.auth.getPermissions(f.user,f.tenant)).thenReturn(List.of(com.dwp.services.auth.dto.PermissionDTO.builder()
                .resourceKey("APP.HRIS").permissionCode("VIEW").effect("ALLOW").build()));
        assertNotEquals(actual.authRevision(),f.truth().loadCurrent(f.tenant,f.user).authRevision());
    }
    @Test void incompleteNativeOwnerCompositionHasNoSyntheticProvider() {
        Fixture f=new Fixture();assertNull(NativeHrisAuthIdentityEvidenceTruthV2.fromNativeServices(null,f.governance,f.duties));
        assertNull(NativeHrisAuthIdentityEvidenceTruthV2.fromNativeServices(f.auth,null,f.duties));
        assertNull(NativeHrisAuthIdentityEvidenceTruthV2.fromNativeServices(f.auth,f.governance,null));
    }
    @Test void initialClockFailureIsGenericAndHasNoSqlOrProviderCall() {
        Fixture f=new Fixture();var source=mock(DataSource.class);var truth=mock(NativeHrisAuthIdentityEvidenceTruthV2.Provider.class);var clock=mock(Clock.class);
        when(clock.instant()).thenThrow(new IllegalStateException("private-secret"));
        denied(()->new NativeHrisCurrentRoleEvidenceReaderV2(source,truth,clock,Duration.ofSeconds(10)).loadCurrent(f.lookup()),NativeHrisCurrentRoleEvidenceReaderV2.Code.UNAVAILABLE);
        verifyNoInteractions(source,truth);
    }
    static void denied(Runnable call,NativeHrisCurrentRoleEvidenceReaderV2.Code code) {
        var error=assertThrows(NativeHrisCurrentRoleEvidenceReaderV2.Rejected.class,call::run);
        assertEquals(code,error.code());assertNull(error.getCause());assertFalse(error.getMessage().contains("private-secret"));
    }
    static final class Fixture {
        long tenant=41,user=900009,version=0,access=0;
        UUID principal=UUID.randomUUID(),person=UUID.randomUUID();Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        AuthService auth=mock(AuthService.class);AppGovernanceService governance=mock(AppGovernanceService.class);
        ScopedAdminDutyEvidenceService duties=mock(ScopedAdminDutyEvidenceService.class);
        Fixture(){when(auth.getPermissions(user,tenant)).thenReturn(List.of());when(auth.getRoleCodes(user,tenant)).thenReturn(List.of());
            when(governance.resourceRoles(tenant,user)).thenReturn(List.of());when(duties.effectiveDuties(tenant,user)).thenReturn(List.of());}
        NativeHrisAuthIdentityEvidenceTruthV2.Provider truth(){return NativeHrisAuthIdentityEvidenceTruthV2.fromNativeServices(auth,governance,duties);}
        CurrentHrisAuthorizationPortsV2.PeopleLookup lookup(){return lookup(truth().loadCurrent(tenant,user).authRevision());}
        CurrentHrisAuthorizationPortsV2.PeopleLookup lookup(String revision) {
            var req=new Requirements("candidate.native.auth.roles.read","hcm","hcm.operations","route.hcm.operations.person-detail.data",
                    "NATIVE_USER_POLICY_READ","dwp-people-server",Plane.MANAGEMENT,AccessMode.NORMAL,Selection.TARGET_POPULATION,
                    TargetKind.PERSON,Set.of("APP.HRIS:VIEW"),Set.of(),Set.of("person.publicId"),false);
            var holder=new AtomicReference<CurrentHrisAuthorizationPortsV2.PeopleLookup>();
            var binding=new AuthPersonBindingV1(tenant,user,principal,person,AuthPersonBindingV1.IdentityPlane.TENANT,
                    AuthPersonBindingV1.Status.ACTIVE,version,access,now,now.plusSeconds(20));
            var guard=new GuardedCurrentHrisAuthorizationPortV2(req,()->new Invocation(tenant,user,principal,person,version,access,
                    req.operationId(),"mock-verified-session",now,now.plusSeconds(20)),
                    q->new DwpAuthoritySnapshot(req,binding,Decision.ALLOWED,new AuthRevision(revision),
                            new PolicyRevision("policy-6-7-"+"b".repeat(64)),new ContextKey("psc-"+"c".repeat(64)),
                            new DecisionRevision("psr-"+"d".repeat(64)),"mock-selected-scope",Set.of("APP.HRIS:VIEW"),Set.of(),true,true,now,now.plusSeconds(20)),
                    q->{holder.set(q);return null;},Clock.fixed(now,ZoneOffset.UTC));
            assertThrows(CurrentHrisAuthorizationExceptionV2.class,()->guard.authorize(new TargetSelector(TargetKind.PERSON,UUID.randomUUID(),null,null,null)));
            if(holder.get()==null)throw new CurrentHrisAuthorizationExceptionV2(CurrentHrisAuthorizationExceptionV2.Code.OWNER_UNAVAILABLE);
            return holder.get();
        }
    }
}
