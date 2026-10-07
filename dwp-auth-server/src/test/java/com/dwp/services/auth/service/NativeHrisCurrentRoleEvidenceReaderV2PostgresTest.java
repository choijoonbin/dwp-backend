package com.dwp.services.auth.service;

import com.dwp.platform.contracts.hris.identity.v2.NativeHrisCurrentRoleEvidenceV2.SourceKind;
import com.dwp.services.auth.repository.RoleMemberRepository;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.dwp.services.auth.service.NativeHrisCurrentRoleEvidenceReaderV2.Code.*;
import static com.dwp.services.auth.service.NativeHrisCurrentRoleEvidenceReaderV2Test.*;

/** Actual native Auth six-origin reads and immutable migrations on owned PG only.
 * Actual IdentityEvidenceService factory/hash + actual RoleMemberRepository SQL run.
 * Underlying AuthService/permission/governance/duty APIs and v2 Gateway admission are
 * EXPLICIT MOCK COMPOSITION, NOT native JPA or signed/installed whole PEP. */
@Testcontainers
class NativeHrisCurrentRoleEvidenceReaderV2PostgresTest {
    private static final String ROLE="native_auth_roles_read_test",PASSWORD=UUID.randomUUID().toString();
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE","postgres:16-alpine"));
    static JdbcTemplate owner;static PGSimpleDataSource runtime;
    static long directRole,groupRole,group,member,assignment;
    Fixture f;NativeHrisCurrentRoleEvidenceReaderV2 reader;
    @BeforeAll static void migrateActualOwnerAndGrantOnlyNativeReadColumns() throws Exception {
        var source=source(POSTGRES.getDatabaseName(),POSTGRES.getUsername(),POSTGRES.getPassword());owner=new JdbcTemplate(source);
        var flyway=Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                .validateOnMigrate(true).outOfOrder(false).load();flyway.migrate();
        assertEquals("242",flyway.info().current().getVersion().getVersion());assertEquals(144,flyway.info().applied().length);
        try(var connection=source.getConnection()) {
            try(var statement=connection.prepareStatement("SELECT set_config('dwp.test_password',?,false)")){statement.setString(1,PASSWORD);statement.execute();}
            try(var statement=connection.createStatement()){statement.execute("""
                    DO $owned$ BEGIN EXECUTE format('CREATE ROLE native_auth_roles_read_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L',current_setting('dwp.test_password')); END $owned$;
                    """);}
        }
        String database=POSTGRES.getDatabaseName();assertTrue(database.matches("[A-Za-z0-9_]+"));
        owner.execute("REVOKE CREATE,TEMPORARY ON DATABASE "+database+" FROM PUBLIC");owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE "+database+" TO "+ROLE);owner.execute("GRANT USAGE ON SCHEMA public TO "+ROLE);
        grant("com_users","tenant_id,user_id,public_id,person_public_id,identity_plane,status,version,access_revision");
        grant("com_roles","tenant_id,role_id,code,status,version,updated_at");
        grant("com_role_members","tenant_id,user_id,role_id,role_member_id,updated_at");
        grant("com_group_role_assignments","tenant_id,group_id,role_id,group_role_assignment_id,version,updated_at,lifecycle_state,assignment_type,scope_type,scope_ref,valid_from,valid_to");
        grant("com_group_members","tenant_id,user_id,group_id,group_member_id,updated_at");grant("com_groups","tenant_id,group_id,status,version,updated_at");
        grant("com_active_privileged_grants","tenant_id,user_id,role_id,active_privileged_grant_id,updated_at,scope_type,scope_ref,revoked_at,activated_at,expires_at");
        runtime=source(database,ROLE,PASSWORD);
        owner.update("INSERT INTO public.com_tenants(tenant_id,code,name) VALUES (41,'native-role-test','Owned roles fixture')");
        owner.update("INSERT INTO public.com_users(user_id,tenant_id,display_name) VALUES (900009,41,'Owned native principal')");
        directRole=owner.queryForObject("INSERT INTO public.com_roles(tenant_id,code,name) VALUES (41,'NATIVE_DIRECT','Native direct') RETURNING role_id",Long.class);
        groupRole=owner.queryForObject("INSERT INTO public.com_roles(tenant_id,code,name) VALUES (41,'NATIVE_GROUP','Native group') RETURNING role_id",Long.class);
        group=owner.queryForObject("INSERT INTO public.com_groups(tenant_id,group_key,display_name) VALUES (41,'native-role-group','Owned group') RETURNING group_id",Long.class);
        owner.update("INSERT INTO public.com_group_members(tenant_id,group_id,user_id) VALUES (41,?,900009)",group);
        member=owner.queryForObject("INSERT INTO public.com_role_members(tenant_id,role_id,user_id) VALUES (41,?,900009) RETURNING role_member_id",Long.class,directRole);
        assignment=owner.queryForObject("INSERT INTO public.com_group_role_assignments(tenant_id,group_id,role_id) VALUES (41,?,?) RETURNING group_role_assignment_id",Long.class,group,groupRole);
    }
    @BeforeEach void resetOnlyOwnedNativeRowsThroughOwner() throws Exception {
        f=new Fixture();
        owner.update("UPDATE public.com_users SET status='ACTIVE',public_id=?,person_public_id=?,version=0,access_revision=0 WHERE tenant_id=41 AND user_id=900009",f.principal,f.person);
        owner.update("UPDATE public.com_roles SET status='ACTIVE',version=0,code='NATIVE_DIRECT' WHERE role_id=?",directRole);
        owner.update("UPDATE public.com_roles SET status='ACTIVE',version=0 WHERE role_id=?",groupRole);
        owner.update("UPDATE public.com_groups SET status='ACTIVE',version=0 WHERE group_id=?",group);
        owner.update("UPDATE public.com_group_role_assignments SET assignment_type='ACTIVE',scope_type='TENANT',scope_ref=NULL,lifecycle_state='ACTIVE',valid_from=NULL,valid_to=NULL,version=0 WHERE group_role_assignment_id=?",assignment);
        String actualQuery=RoleMemberRepository.class.getMethod("findRoleIds",Long.class,Long.class).getAnnotation(Query.class).value();
        when(f.auth.getRoleCodes(f.user,f.tenant)).thenAnswer(ignored->{
            var ids=new NamedParameterJdbcTemplate(owner).queryForList(actualQuery,Map.of("tenantId",f.tenant,"userId",f.user),Long.class);
            if(ids.isEmpty())return List.of();
            return new NamedParameterJdbcTemplate(owner).queryForList("SELECT code FROM public.com_roles WHERE tenant_id=:tenant AND role_id IN (:ids) AND status='ACTIVE' ORDER BY code",Map.of("tenant",f.tenant,"ids",ids),String.class);
        });
        reader=new NativeHrisCurrentRoleEvidenceReaderV2(runtime,f.truth(),Clock.fixed(f.now,ZoneOffset.UTC),Duration.ofSeconds(10));
    }
    @Test void completeNativeDirectAndTenantGroupSourcesReuseActualOwnerHash() {
        var evidence=reader.loadCurrent(f.lookup());assertTrue(evidence.complete());assertEquals(2,evidence.roles().size());
        assertEquals(f.truth().loadCurrent(41,900009).authRevision(),evidence.authRevision());assertEquals(0,evidence.userRowVersion());assertEquals(0,evidence.accessRevision());
        var direct=evidence.roles().stream().filter(r->r.sourceKind()==SourceKind.DIRECT).findFirst().orElseThrow();
        assertEquals(member,direct.sourceId());assertNull(direct.sourceVersion());assertNull(direct.validFrom());assertNull(direct.validTo());
        var inherited=evidence.roles().stream().filter(r->r.sourceKind()==SourceKind.GROUP).findFirst().orElseThrow();
        assertEquals(assignment,inherited.sourceId());assertEquals(0L,inherited.sourceVersion());assertEquals(group,inherited.groupId());assertEquals(0L,inherited.groupVersion());assertNotNull(inherited.membershipStamp());
        assertTrue(owner.queryForObject("SELECT to_regclass('public.ppl_persons') IS NULL",Boolean.class));
    }
    enum Excluded { GROUP_INACTIVE, GROUP_REVOKED, GROUP_ELIGIBLE, ORG_SCOPED, RESOURCE_SCOPED, FUTURE_WINDOW, EXPIRED_WINDOW, ROLE_INACTIVE }
    @ParameterizedTest @EnumSource(Excluded.class) void scopedOrIneffectiveGroupsNeverBecomeTenantRoles(Excluded excluded) {
        switch(excluded) {
            case GROUP_INACTIVE->owner.update("UPDATE public.com_groups SET status='INACTIVE' WHERE group_id=?",group);
            case GROUP_REVOKED->owner.update("UPDATE public.com_group_role_assignments SET lifecycle_state='REVOKED' WHERE group_role_assignment_id=?",assignment);
            case GROUP_ELIGIBLE->owner.update("UPDATE public.com_group_role_assignments SET assignment_type='ELIGIBLE' WHERE group_role_assignment_id=?",assignment);
            case ORG_SCOPED,RESOURCE_SCOPED->owner.update("UPDATE public.com_group_role_assignments SET scope_type=?,scope_ref='explicit-owner-scope' WHERE group_role_assignment_id=?",excluded==Excluded.ORG_SCOPED?"ORG_UNIT":"RESOURCE",assignment);
            case FUTURE_WINDOW->owner.update("UPDATE public.com_group_role_assignments SET valid_from=? WHERE group_role_assignment_id=?",Timestamp.from(f.now.plusSeconds(60)),assignment);
            case EXPIRED_WINDOW->owner.update("UPDATE public.com_group_role_assignments SET valid_to=? WHERE group_role_assignment_id=?",Timestamp.from(f.now.minusSeconds(1)),assignment);
            case ROLE_INACTIVE->owner.update("UPDATE public.com_roles SET status='INACTIVE' WHERE role_id=?",groupRole);
        }
        assertEquals(Set.of("NATIVE_DIRECT"),reader.loadCurrent(f.lookup()).roles().stream().map(r->r.roleCode()).collect(java.util.stream.Collectors.toSet()));
    }
    enum ActorMismatch { REVOKED, NATIVE_ROW_VERSION, ACCESS_REVISION, PRINCIPAL_RELABEL, PERSON_RELINK, FOREIGN_TENANT }
    @ParameterizedTest @EnumSource(ActorMismatch.class) void currentNativeActorIsRefetchedNotSuppliedOrCached(ActorMismatch mismatch) {
        var lookup=f.lookup();reader.loadCurrent(lookup);
        switch(mismatch) {
            case REVOKED->owner.update("UPDATE public.com_users SET status='INACTIVE' WHERE user_id=900009");
            case NATIVE_ROW_VERSION->owner.update("UPDATE public.com_users SET version=1 WHERE user_id=900009");
            case ACCESS_REVISION->owner.update("UPDATE public.com_users SET access_revision=1 WHERE user_id=900009");
            case PRINCIPAL_RELABEL->owner.update("UPDATE public.com_users SET public_id=? WHERE user_id=900009",UUID.randomUUID());
            case PERSON_RELINK->owner.update("UPDATE public.com_users SET person_public_id=? WHERE user_id=900009",UUID.randomUUID());
            case FOREIGN_TENANT->{f.tenant=42;lookup=f.lookup();}
        }
        var rejectedLookup=lookup;denied(()->reader.loadCurrent(rejectedLookup),INVALID);
    }
    @Test void nonSelfActorWithoutNativePersonCanReadOwnNativeRolesWithoutUuidEqualityFallback() {
        owner.update("UPDATE public.com_users SET person_public_id=NULL WHERE user_id=900009");f.person=null;
        assertEquals(2,reader.loadCurrent(f.lookup()).roles().size());
    }
    @Test void legitimateNativeDotHyphenCodesUseTheActualOwnerNormalization() {
        owner.update("UPDATE public.com_roles SET code='native.read-role' WHERE role_id=?",directRole);
        assertTrue(reader.loadCurrent(f.lookup()).roles().stream().anyMatch(r->r.roleCode().equals("NATIVE.READ-ROLE")));
    }
    @Test void actualUiResourceGrantEvidenceWithRoleIdZeroIsNotNativeRoleAuthority() {
        var id=UUID.randomUUID();
        owner.update("""
                INSERT INTO public.com_principal_resource_grants(principal_resource_grant_id,tenant_id,principal_type,principal_ref,
                    resource_id,permission_id,source_type,source_ref,justification,valid_from)
                SELECT ?,41,'USER','900009',resource_id,permission_id,'ADMIN_DIRECT',?,'Owned test entitlement only',CURRENT_TIMESTAMP-interval '1 second'
                FROM public.com_resources CROSS JOIN public.com_permissions
                WHERE public.com_resources.key='APP.HCM' AND public.com_permissions.code='VIEW'
                """,id,id.toString());
        var ui=new com.dwp.services.auth.repository.IdentityAccessEvidenceRepository(new NamedParameterJdbcTemplate(owner));
        assertTrue(ui.effectiveAccess(41L,List.of(900009L)).get(900009L).stream().anyMatch(r->r.roleId()==0));
        assertEquals(Set.of("NATIVE_DIRECT","NATIVE_GROUP"),reader.loadCurrent(f.lookup()).roles().stream().map(r->r.roleCode()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void completeSourceSetOver100RejectsRatherThanReturningTruncatedAuthority() {
        var ids=new ArrayList<Long>();
        try {
            for(int index=0;index<99;index++) {
                long role=owner.queryForObject("INSERT INTO public.com_roles(tenant_id,code,name) VALUES (41,?,'Owned limit role') RETURNING role_id",Long.class,"NATIVE_LIMIT_"+index);
                ids.add(role);owner.update("INSERT INTO public.com_role_members(tenant_id,role_id,user_id) VALUES (41,?,900009)",role);
            }
            denied(()->reader.loadCurrent(f.lookup()),LIMIT_EXCEEDED);
        } finally {
            for(long id:ids)owner.update("UPDATE public.com_roles SET status='INACTIVE' WHERE role_id=?",id);
        }
    }
    @Test void nativeUserChangeInsideSecondOwnerReadCannotEmitCurrentEvidence() {
        var lookup=f.lookup();var calls=new AtomicInteger();var truth=f.truth();
        var changing=(NativeHrisAuthIdentityEvidenceTruthV2.Provider)(tenant,user)->{var result=truth.loadCurrent(tenant,user);
            if(calls.incrementAndGet()==1)owner.update("UPDATE public.com_users SET access_revision=access_revision+1 WHERE user_id=900009");return result;};
        denied(()->new NativeHrisCurrentRoleEvidenceReaderV2(runtime,changing,Clock.fixed(f.now,ZoneOffset.UTC),Duration.ofSeconds(10)).loadCurrent(lookup),INVALID);
    }
    @Test void nativeRoleVersionRefetchFindsChangeEvenWhenRoleCodesAndAuthHashRemainSame() {
        var lookup=f.lookup();var calls=new AtomicInteger();var truth=f.truth();
        var changing=(NativeHrisAuthIdentityEvidenceTruthV2.Provider)(tenant,user)->{var result=truth.loadCurrent(tenant,user);
            if(calls.incrementAndGet()==1)owner.update("UPDATE public.com_roles SET version=version+1 WHERE role_id=?",directRole);return result;};
        denied(()->new NativeHrisCurrentRoleEvidenceReaderV2(runtime,changing,Clock.fixed(f.now,ZoneOffset.UTC),Duration.ofSeconds(10)).loadCurrent(lookup),SOURCE_CHANGED);
    }
    @Test void finalNativeActorRefetchFindsChangeDuringLastTruthCall() {
        var lookup=f.lookup();var calls=new AtomicInteger();var truth=f.truth();
        var changing=(NativeHrisAuthIdentityEvidenceTruthV2.Provider)(tenant,user)->{var result=truth.loadCurrent(tenant,user);
            if(calls.incrementAndGet()==2)owner.update("UPDATE public.com_users SET version=version+1 WHERE user_id=900009");return result;};
        denied(()->new NativeHrisCurrentRoleEvidenceReaderV2(runtime,changing,Clock.fixed(f.now,ZoneOffset.UTC),Duration.ofSeconds(10)).loadCurrent(lookup),INVALID);
    }
    @Test void finalNativeGroupStampRefetchFindsChangeWithoutInventingMembershipVersion() {
        var lookup=f.lookup();var calls=new AtomicInteger();var truth=f.truth();
        var changing=(NativeHrisAuthIdentityEvidenceTruthV2.Provider)(tenant,user)->{var result=truth.loadCurrent(tenant,user);
            if(calls.incrementAndGet()==2)owner.update("UPDATE public.com_groups SET updated_at=updated_at+interval '1 second' WHERE group_id=?",group);return result;};
        denied(()->new NativeHrisCurrentRoleEvidenceReaderV2(runtime,changing,Clock.fixed(f.now,ZoneOffset.UTC),Duration.ofSeconds(10)).loadCurrent(lookup),SOURCE_CHANGED);
    }
    @Test void actualOwnerAuthRevisionCannotBeReplacedWithAValidLookingOtherDigest() {
        denied(()->reader.loadCurrent(f.lookup("auth-"+"a".repeat(64))),INVALID);
    }
    @Test void providerFailureRedactsSecretAndOwnerCallsDoNotPassExpiredLease() {
        var lookup=f.lookup();denied(()->new NativeHrisCurrentRoleEvidenceReaderV2(runtime,(t,u)->{throw new IllegalStateException("private-secret");},
                Clock.fixed(f.now,ZoneOffset.UTC),Duration.ofSeconds(10)).loadCurrent(lookup),UNAVAILABLE);
        var time=new AtomicReference<>(f.now);var calls=new AtomicInteger();var truth=f.truth();
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time.get();}};
        denied(()->new NativeHrisCurrentRoleEvidenceReaderV2(runtime,(t,u)->{var result=truth.loadCurrent(t,u);if(calls.incrementAndGet()==2)time.set(f.now.plusSeconds(10));return result;},clock,Duration.ofSeconds(10)).loadCurrent(lookup),STALE);
    }
    @Test void nativeSqlPrivilegeLossAndRuntimeDdlTempWriteHistoryRemainDenied() throws Exception {
        JdbcTemplate query=new JdbcTemplate(runtime);assertTrue(query.queryForObject("SELECT current_user=session_user AND current_user=?",Boolean.class,ROLE));
        assertFalse(query.queryForObject("SELECT has_column_privilege(current_user,'public.com_users','display_name','SELECT')",Boolean.class));
        for(String sql:List.of("UPDATE public.com_users SET status='INACTIVE' WHERE user_id=900009","CREATE TABLE public.forbidden_native_role(id bigint)",
                "CREATE TEMPORARY TABLE forbidden_native_role(id bigint)","SELECT * FROM public.flyway_schema_history","SET ROLE "+POSTGRES.getUsername())) {
            try(var connection=runtime.getConnection();var statement=connection.createStatement()) {
                var error=assertThrows(SQLException.class,()->statement.execute(sql));assertEquals("42501",error.getSQLState());
            }
        }
        owner.execute("REVOKE SELECT (status) ON public.com_roles FROM "+ROLE);
        try{denied(()->reader.loadCurrent(f.lookup()),UNAVAILABLE);}finally{grant("com_roles","status");}
    }
    @Test void current212KeepsPrivilegedActivationDisabledRatherThanCreatingHealthyJitFixture() throws Exception {
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM public.com_active_privileged_grants WHERE revoked_at IS NULL",Integer.class));
        try(var connection=source(POSTGRES.getDatabaseName(),POSTGRES.getUsername(),POSTGRES.getPassword()).getConnection();var statement=connection.prepareStatement("""
                INSERT INTO public.com_active_privileged_grants(privileged_access_request_id,tenant_id,user_id,role_id,activated_at,expires_at)
                VALUES (?,41,900009,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '1 hour')
                """)) {
            statement.setObject(1,UUID.randomUUID());statement.setLong(2,directRole);
            var error=assertThrows(SQLException.class,statement::execute);assertEquals("23514",error.getSQLState());
        }
        assertTrue(reader.loadCurrent(f.lookup()).roles().stream().noneMatch(r->r.sourceKind()==SourceKind.PRIVILEGED));
    }
    private static void grant(String table,String columns){owner.execute("GRANT SELECT ("+columns+") ON public."+table+" TO "+ROLE);}
    private static PGSimpleDataSource source(String database,String user,String password){
        var result=new PGSimpleDataSource();result.setServerNames(new String[]{POSTGRES.getHost()});result.setPortNumbers(new int[]{POSTGRES.getMappedPort(5432)});
        result.setDatabaseName(database);result.setUser(user);result.setPassword(password);result.setConnectTimeout(5);result.setSocketTimeout(10);return result;
    }
}
