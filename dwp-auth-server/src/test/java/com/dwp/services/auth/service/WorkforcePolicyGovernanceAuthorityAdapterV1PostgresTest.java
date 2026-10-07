package com.dwp.services.auth.service;

import static com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.*;
import static com.dwp.services.auth.service.WorkforcePolicyGovernanceAuthorityAdapterV1Test.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.services.auth.config.JwtConfig;
import com.dwp.services.auth.config.SecurityExceptionHandler;
import com.dwp.services.auth.config.WorkforcePolicyGovernanceInternalSecurityConfigV1;
import com.dwp.services.auth.controller.WorkforcePolicyGovernanceAuthorityControllerV1;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos.BundleContract;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.*;
import com.dwp.services.auth.security.AuthSessionJwtTokenEncoder;
import com.dwp.services.auth.security.AuthSessionJwtValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.mockito.Mockito.*;

/** Owned native Auth PG + actual JPA repositories/AuthService/identity factory,
 * actual product evaluator, actual JwtConfig decoder/session validator/encoder.
 * NEW capability/duty/SoD rows are TEST_ONLY owner fixtures, not registration,
 * installed transport/People command authorization or S2/S3 permits. */
@Testcontainers
class WorkforcePolicyGovernanceAuthorityAdapterV1PostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE","postgres:16-alpine"));
    static final String READ_ROLE="workforce_s1_native_read_test",PASSWORD=UUID.randomUUID().toString();
    static final String JWT_SECRET="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String TEST_RELEASE_CHECKSUM="739b7bdcb6d4533544c0488b5d7d85f922b576936df2db0c1c22d362404859a1";
    static final long TENANT=41,ACTOR=900009,MAKER=941010,CHECKER=941011,TARGET=941012;
    static final UUID PRINCIPAL=UUID.fromString("44444444-4444-4444-8444-444444444444");
    static final ObjectMapper MAPPER=new ObjectMapper().findAndRegisterModules();
    static JdbcTemplate owner,jdbc;static PGSimpleDataSource runtime;
    static LocalContainerEntityManagerFactoryBean emfBean;static EntityManagerFactory emf;
    static EntityManager shared;static JpaTransactionManager transactions;
    static ProductAuthorizationContractRepository registry;
    static ProductAuthorizationIdentityEvidenceService identities;
    static AuthService auth;static JwtDecoder decoder;
    static UUID set,responsibility,duty;static long nativeRole;static Code baselineCode;
    static String providerRole;
    Instant now;AtomicReference<Instant> time;Clock clock;String token,jti;UUID session,family;
    WorkforcePolicyGovernanceAuthorityAdapterV1 adapter;
    @BeforeAll static void actualMigrationsOnlyAndRealNativeReadDependencies()throws Exception {
        var owned=source(POSTGRES.getUsername(),POSTGRES.getPassword());owner=new JdbcTemplate(owned);
        var flyway=Flyway.configure().dataSource(owned).locations("filesystem:src/main/resources/db/migration")
                .validateOnMigrate(true).outOfOrder(false).load();flyway.migrate();
        assertEquals("242",flyway.info().current().getVersion().getVersion());
        assertEquals(144,flyway.info().applied().length);
        owner.update("INSERT INTO public.com_tenants(tenant_id,code,name) VALUES (41,'s1-native-owned','TEST_ONLY S1 owner')");
        for(long user:List.of(ACTOR,MAKER,CHECKER,TARGET))owner.update(
                "INSERT INTO public.com_users(user_id,tenant_id,display_name) VALUES (?,41,'TEST_ONLY native actor')",user);
        owner.update("UPDATE public.com_users SET public_id=? WHERE user_id=?",PRINCIPAL,ACTOR);
        owner.update("""
                INSERT INTO public.com_resources(tenant_id,type,key,name,enabled)
                SELECT 41,resource_type,resource_key,display_name,TRUE FROM public.sys_tenant_resource_templates
                WHERE resource_key IN ('APP.HCM','ADMIN.WORKFORCE_ACCESS')
                ON CONFLICT(tenant_id,type,key) DO NOTHING
                """);
        owner.update("INSERT INTO public.com_resources(tenant_id,type,key,name,enabled) VALUES (41,'APP','APP.S1_TEST_ONLY','TEST_ONLY nonroot member',TRUE)");
        set=UUID.randomUUID();responsibility=UUID.randomUUID();duty=UUID.randomUUID();
        owner.update("""
                INSERT INTO public.com_admin_resource_sets(resource_set_id,tenant_id,resource_set_key,name,resource_type,lifecycle_state)
                VALUES (?,41,'RS_HCM_CONFIG','TEST_ONLY native HCM set','APP','ACTIVE')
                """,set);
        for(String key:List.of("APP.HCM","APP.S1_TEST_ONLY"))owner.update("""
                INSERT INTO public.com_admin_resource_set_members(tenant_id,resource_set_id,resource_type,resource_key)
                VALUES (41,?,'APP',?)
                """,set,key);
        owner.update("""
                INSERT INTO public.com_admin_role_assignments(admin_role_assignment_id,tenant_id,principal_type,principal_ref,
                    responsibility_code,resource_set_id,lifecycle_state,review_due_at,justification,approved_by,approved_at)
                VALUES (?,41,'USER','900009','APP_CONFIG_ADMIN',?,'ACTIVE',CURRENT_TIMESTAMP+interval '1 day',
                    'TEST_ONLY approved native configuration responsibility',941011,CURRENT_TIMESTAMP)
                """,responsibility,set);
        nativeRole=owner.queryForObject("""
                INSERT INTO public.com_roles(tenant_id,code,name,builtin_role_code,role_type)
                VALUES (41,'HR_ADMIN','TEST_ONLY native PEOPLE family','HR_ADMIN','SYSTEM') RETURNING role_id
                """,Long.class);
        owner.update("INSERT INTO public.com_role_members(tenant_id,role_id,user_id) VALUES (41,?,900009)",nativeRole);
        for(String code:List.of("R","A.B","A-B","A"+"B".repeat(49)))owner.update(
                "INSERT INTO public.com_roles(tenant_id,code,name) VALUES (41,?,'TEST_ONLY native role target')",code);
        providerRole=owner.queryForObject("SELECT role_code FROM public.sys_builtin_role_catalog WHERE role_family='PROVIDER' AND lifecycle_state='ACTIVE' ORDER BY role_code LIMIT 1",String.class);
        owner.update("INSERT INTO public.com_roles(tenant_id,code,name,builtin_role_code,role_type) VALUES (41,?,'TEST_ONLY excluded provider',?,'SYSTEM')",providerRole,providerRole);
        owner.update("""
                INSERT INTO public.com_role_permissions(tenant_id,role_id,resource_id,permission_id,effect)
                SELECT 41,?,r.resource_id,p.permission_id,'ALLOW' FROM public.com_resources r CROSS JOIN public.com_permissions p
                WHERE r.tenant_id=41 AND ((r.key='APP.HCM' AND p.code='VIEW') OR (r.key='ADMIN.WORKFORCE_ACCESS' AND p.code='MANAGE'))
                """,nativeRole);
        try(var connection=owned.getConnection()){
            try(var statement=connection.prepareStatement("SELECT set_config('dwp.test_password',?,false)")){statement.setString(1,PASSWORD);statement.execute();}
            try(var statement=connection.createStatement()){statement.execute("""
                    DO $owned$ BEGIN EXECUTE format('CREATE ROLE workforce_s1_native_read_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L',current_setting('dwp.test_password')); END $owned$;
                    """);}
        }
        String db=POSTGRES.getDatabaseName();assertTrue(db.matches("[A-Za-z0-9_]+"));
        owner.execute("REVOKE CREATE,TEMPORARY ON DATABASE "+db+" FROM PUBLIC");owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE "+db+" TO "+READ_ROLE);owner.execute("GRANT USAGE ON SCHEMA public TO "+READ_ROLE);
        for(String table:List.of("com_users","com_tenants","com_roles","com_role_members","com_group_role_assignments","com_groups",
                "com_group_members","com_active_privileged_grants","com_role_permissions","com_resources","com_permissions",
                "com_principal_resource_grants","sys_auth_sessions","sys_builtin_role_catalog","com_admin_resource_sets",
                "com_admin_resource_set_members","com_admin_role_assignments","sys_admin_scoped_duty_catalog",
                "sys_admin_scoped_duty_capabilities","sys_admin_scoped_duty_conflicts","auth_effective_scoped_duties",
                "auth_product_authorization_active","auth_product_authorization_bundle","auth_product_capability_contract",
                "auth_product_access_policy","auth_product_entitlement_expression","auth_product_predicate_policy",
                "auth_governed_route_contract","auth_product_authority_endpoint"))owner.execute("GRANT SELECT ON public."+table+" TO "+READ_ROLE);
        runtime=source(READ_ROLE,PASSWORD);jdbc=new JdbcTemplate(runtime);
        emfBean=new LocalContainerEntityManagerFactoryBean();emfBean.setDataSource(runtime);
        emfBean.setPackagesToScan("com.dwp.services.auth.entity");emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emfBean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none","hibernate.jdbc.time_zone","UTC",
                "hibernate.show_sql","false","hibernate.format_sql","false"));emfBean.afterPropertiesSet();
        emf=Objects.requireNonNull(emfBean.getObject());shared=SharedEntityManagerCreator.createSharedEntityManager(emf);
        transactions=new JpaTransactionManager(emf);transactions.setDataSource(runtime);
        var repos=new JpaRepositoryFactory(shared);
        var users=repos.getRepository(UserRepository.class);var roles=repos.getRepository(RoleRepository.class);
        var members=repos.getRepository(RoleMemberRepository.class);
        var duties=new ScopedAdminDutyEvidenceService(jdbc);var governance=new AppGovernanceService(jdbc,null);
        // Login/mutation-only collaborators are absent, not mock read authorization.
        auth=new AuthService(users,null,null,roles,members,null,null,repos.getRepository(RolePermissionRepository.class),
                repos.getRepository(ResourceRepository.class),repos.getRepository(PermissionRepository.class),
                new PrincipalResourceGrantRepository(jdbc),null,null,null,null,null,governance,duties,null);
        identities=new ProductAuthorizationIdentityEvidenceService(auth,governance,duties);
        registry=new ProductAuthorizationContractRepository(jdbc,MAPPER);
        var jwtConfig=new JwtConfig(mock(SecurityExceptionHandler.class));ReflectionTestUtils.setField(jwtConfig,"jwtSecret",JWT_SECRET);
        decoder=ReflectionTestUtils.invokeMethod(jwtConfig,"jwtDecoder",new AuthSessionJwtValidator(
                repos.getRepository(AuthSessionRepository.class),members,roles,users));
        var baseline=new WorkforcePolicyGovernanceAuthorityAdapterV1PostgresTest();baseline.resetNativeFixture();
        baselineCode=assertInstanceOf(Failure.class,baseline.adapter.evaluate(bytes(baseline.nativeRequest(request("POLICY_LIST_AUTH_READ"))),baseline.token)).code();
        assertEquals(Code.OPERATION_UNREGISTERED,baselineCode);
        installTestOnlyCatalogThroughActualNativeLifecycle();
    }
    static void installTestOnlyCatalogThroughActualNativeLifecycle()throws Exception{
        var ownerRegistry=new ProductAuthorizationContractRepository(owner,MAPPER);
        var productionValidator=new ProductAuthorizationContractValidator(MAPPER);
        BundleContract base;
        try(var input=new org.springframework.core.io.ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v5.generated.json").getInputStream()){
            base=productionValidator.validateDocument(input);
        }
        ObjectNode doc=MAPPER.valueToTree(base);doc.put("version",base.version()+1);doc.put("bundleStatus","DRAFT");
        ObjectNode cap=MAPPER.createObjectNode();cap.put("contractKey",CAPABILITY).put("resolvedCapabilityCode","ADMIN.WORKFORCE_ACCESS:MANAGE")
                .put("mappingVersion",1).put("productKey","hcm").put("surfaceKey","hcm.management");
        cap.putArray("routeContractKeys");cap.put("resourceKey","ADMIN.WORKFORCE_ACCESS").put("action","MANAGE")
                .put("authorityMode","PERMISSION").put("responsibilityRequirement","REQUIRED").put("requiredResponsibilityCode","APP_CONFIG_ADMIN")
                .put("scopeResolver","APP_RESOURCE_SET:RS_HCM_CONFIG").put("riskTier","LOW").putNull("activationPolicy");
        cap.put("sodPolicyId",SOD).put("requiresProductEntitlement",true).put("owner","TEST_ONLY native S1")
                .putNull("sunsetAt").putNull("legacySource").put("policyVersion",1).put("lifecycleState","ACTIVE");
        ((ArrayNode)doc.path("capabilities")).add(cap);
        ObjectNode policy=MAPPER.createObjectNode();policy.put("accessPolicyKey","hcm.workforce-governance-test-only-entry.v1")
                .put("navigationContextId","hcm.management").put("productKey","hcm").put("surfaceKey","hcm.management");
        policy.putArray("surfaceEntryKeys").add("hcm.management");policy.put("evaluationType","SINGLE").put("authorityMode","ENTITLEMENT")
                .put("entitlementExpressionKey","HCM_PERSONAL_CORE_ACCESS_V1").put("requiresProductEntitlement",true)
                .putNull("relationshipResolver").put("scopeResolver","SELF");policy.putNull("supportScopes");policy.putNull("modeBranches");
        policy.putArray("routeContractKeys");policy.put("owner","TEST_ONLY native S1").put("policyVersion",1).put("lifecycleState","ACTIVE");
        ((ArrayNode)doc.path("accessPolicies")).add(policy);
        doc.put("checksum",productionValidator.checksum(doc));
        assertEquals(TEST_RELEASE_CHECKSUM,doc.path("checksum").asText());
        assertThrows(IllegalArgumentException.class,()->productionValidator.validateDocument(doc),
                "A test-only release must remain outside the production immutable lineage");
        var validator=new TestOnlyReleaseValidator(MAPPER);
        BundleContract candidate=validator.validateDocument(doc);var service=new ProductAuthorizationContractService(ownerRegistry,validator);
        var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource()));
        tx.executeWithoutResult(ignored->{
            service.importDraft(candidate);
            service.approveGoverned(candidate.bundleKey(),candidate.version(),candidate.checksum(),"test-only-maker","test-only-checker","TEST-ONLY-S1");
            long revision=ownerRegistry.findActivePointer(candidate.bundleKey()).map(pointer->pointer.revision()).orElse(0L);
            service.activateGoverned(candidate.bundleKey(),candidate.version(),candidate.checksum(),"test-only-release",revision,"TEST-ONLY-S1");
        });
        owner.update("""
                INSERT INTO public.sys_admin_scoped_duty_catalog(duty_code,product_key,legacy_role_code,product_resource_key,
                    resource_key,risk_tier) VALUES (?,'hcm','HR_ADMIN','APP.HCM','ADMIN.WORKFORCE_ACCESS','LOW')
                """,DUTY);
        owner.update("""
                INSERT INTO public.sys_admin_scoped_duty_capabilities(duty_code,capability_contract_key,permission_resource_key,permission_code)
                VALUES (?,?,'ADMIN.WORKFORCE_ACCESS','MANAGE')
                """,DUTY,CAPABILITY);
        owner.update("""
                INSERT INTO public.sys_admin_scoped_duty_conflicts(left_duty_code,right_duty_code,sod_policy_id)
                VALUES ('APPROVAL_OPERATIONS_AUDIT',?,?)
                """,DUTY,SOD);
        owner.update("""
                INSERT INTO public.com_admin_scoped_duty_assignments(scoped_duty_assignment_id,tenant_id,principal_type,principal_ref,
                    duty_code,resource_set_id,responsibility_assignment_id,lifecycle_state,review_due_at,justification,requested_by,
                    approved_by,approved_at,decision_reason)
                VALUES (?,41,'USER','900009',?,?,?,'ACTIVE',CURRENT_TIMESTAMP+interval '1 day',
                    'TEST_ONLY independently approved S1 native read duty',941010,941011,CURRENT_TIMESTAMP,'TEST_ONLY S1 read')
                """,duty,DUTY,set,responsibility);
    }
    static final class TestOnlyReleaseValidator extends ProductAuthorizationContractValidator {
        TestOnlyReleaseValidator(ObjectMapper mapper){super(mapper);}
        @Override boolean supportsDescriptorStructure(long version){return version==6;}
        @Override void validateReleaseLineage(BundleContract contract){
            if(contract.version()!=6||!TEST_RELEASE_CHECKSUM.equals(contract.checksum())
                    ||contract.capabilities().size()!=73||contract.accessPolicies().size()!=23
                    ||contract.entitlementExpressions().size()!=16||contract.predicatePolicies().size()!=33
                    ||contract.routes().size()!=160)
                throw new IllegalArgumentException("Test-only release manifest drift.");
        }
    }
    @AfterAll static void closeOwnedResources(){if(emfBean!=null)emfBean.destroy();}
    @BeforeEach void resetNativeFixture(){
        now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);time=new AtomicReference<>(now);
        clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time.get();}};
        owner.update("UPDATE public.com_users SET public_id=?,person_public_id=NULL,identity_plane='TENANT',status='ACTIVE',version=0,access_revision=0 WHERE tenant_id=41 AND user_id=900009",PRINCIPAL);
        owner.update("UPDATE public.com_users SET status='ACTIVE',identity_plane='TENANT',version=0 WHERE tenant_id=41 AND user_id=941012");
        owner.update("UPDATE public.com_tenants SET status='ACTIVE',version=0 WHERE tenant_id=41");
        owner.update("UPDATE public.com_roles SET status='ACTIVE',version=0 WHERE role_id=?",nativeRole);
        owner.update("UPDATE public.com_admin_resource_sets SET lifecycle_state='ACTIVE',version=0 WHERE resource_set_id=?",set);
        owner.update("UPDATE public.com_admin_role_assignments SET lifecycle_state='ACTIVE',valid_to=NULL WHERE admin_role_assignment_id=?",responsibility);
        owner.update("UPDATE public.sys_admin_scoped_duty_catalog SET lifecycle_state='ACTIVE' WHERE duty_code=?",DUTY);
        owner.update("UPDATE public.sys_admin_scoped_duty_conflicts SET lifecycle_state='ACTIVE',sod_policy_id=? WHERE right_duty_code=?",SOD,DUTY);
        owner.update("UPDATE public.com_admin_scoped_duty_assignments SET lifecycle_state='ACTIVE',valid_to=NULL WHERE scoped_duty_assignment_id=?",duty);
        jti=UUID.randomUUID().toString();session=UUID.randomUUID();family=UUID.randomUUID();
        owner.update("""
                INSERT INTO public.sys_auth_sessions(session_id,token_id,session_family_id,tenant_id,user_id,expires_at,
                    session_started_at,issued_at,last_seen_at,idle_expires_at)
                VALUES (?,?,?,41,900009,?,?,?,?,?)
                """,session,jti,family,Timestamp.from(now.plusSeconds(300)),Timestamp.from(now),Timestamp.from(now),
                Timestamp.from(now),Timestamp.from(now.plusSeconds(300)));
        token=new AuthSessionJwtTokenEncoder(JWT_SECRET,MAPPER).encode(new AuthSessionJwtTokenEncoder.SessionTokenClaims(
                ACTOR,TENANT,List.of("HR_ADMIN"),jti,family,now,now.plusSeconds(300),null,null,List.of()));
        adapter=build(decoder,identities);
    }
    WorkforcePolicyGovernanceAuthorityAdapterV1 build(JwtDecoder jwt,ProductAuthorizationIdentityEvidenceService truth){
        return new WorkforcePolicyGovernanceAuthorityAdapterV1(jdbc,truth,new ProductAuthorizationAuthorityAdapter(
                registry,truth,clock,"urn:dwp:acr:mfa"),
                registry,transactions,jwt,clock);
    }
    ObjectNode nativeRequest(ObjectNode n){
        ((ObjectNode)n.path("governanceScope")).put("resourceSetId",set.toString()).put("selectedScopeKey",ProductAuthorizationAuthoritySupport.scopeKey(
                new ProductSurfaceAuthorityDtos.EvaluateRequest(TENANT,ACTOR,"hcm","hcm.management",ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                    null,null,null,null,null,List.of()),"RS_HCM_CONFIG","RESOURCE_SET"));return n;
    }
    Success success(ObjectNode n){
        Response response=adapter.evaluate(bytes(nativeRequest(n)),token);
        if(response instanceof Failure failure)fail("Expected Success but was Failure code="+failure.code());
        return assertInstanceOf(Success.class,response);
    }
    void denied(ObjectNode n,Code expected){assertEquals(expected,assertInstanceOf(Failure.class,adapter.evaluate(bytes(nativeRequest(n)),token)).code());}
    @Test void baselineNativeUnregisteredCapabilityNeverBecomesBroadManagePermit(){assertEquals(Code.OPERATION_UNREGISTERED,baselineCode);}
    @ParameterizedTest @EnumSource(Operation.class)
    void testOnlyNativeCatalogActualFactoryEvaluatorJwtAndSessionProduceReadEvidence(Operation op){
        ObjectNode n=switch(op){case POLICY_CREATE_PREFLIGHT_AUTH_READ->create("HR_ADMIN");case POLICY_REVOKE_PREFLIGHT_AUTH_READ->revoke("A"+"B".repeat(79));default->request(op.name());};
        var result=success(n);assertEquals(BOUNDARY,result.boundary());assertEquals(op,result.operationId());assertNull(result.actor().personPublicId());
        assertEquals(PRINCIPAL.toString(),result.actor().principalPublicId());assertEquals("0",result.actor().userRowVersion());
        assertEquals(identities.load(TENANT,ACTOR).revision(),result.authPolicy().authRevision());
        assertEquals("SOD-HRIS-WORKFORCE-POLICY-AUTH-READ-V1",result.authPolicy().knownSodPolicyId());
        assertTrue(result.authPolicy().policyRevision().startsWith("policy-"));assertEquals(duty.toString(),result.authPolicy().dutyEvidence().getFirst().assignmentId());
        assertEquals("NOT_PERFORMED_PEOPLE_NATIVE_REFETCH_REQUIRED",result.peopleOwnerValidation());
        assertTrue(Duration.between(Instant.parse(result.capturedAt()),Instant.parse(result.expiresAt())).getSeconds()<=30);
        if(op==Operation.POLICY_CREATE_PREFLIGHT_AUTH_READ)assertEquals("PEOPLE",assertInstanceOf(NativeRoleFact.class,result.subject()).catalogFamily());
        if(op==Operation.POLICY_REVOKE_PREFLIGHT_AUTH_READ)assertInstanceOf(LegacySubjectFact.class,result.subject());
        assertTrue(owner.queryForObject("SELECT to_regclass('public.ppl_persons') IS NULL",Boolean.class));
    }
    @Test void nativeOneLetterDotHyphenAndFiftyCharacterRoleTargetsAreActualCatalogRows(){
        for(String code:List.of("R","A.B","A-B","A"+"B".repeat(49)))assertEquals(code,assertInstanceOf(NativeRoleFact.class,success(create(code)).subject()).code());
        denied(create(providerRole),Code.PROVIDER_PLANE_DENIED);
    }
    @Test void inactiveUserRevocationExpectationAndSelfSubjectRemainReadNotPeopleCommandAuthorization(){
        owner.update("UPDATE public.com_users SET status='INACTIVE' WHERE user_id=941012");
        var create=create("R");((ObjectNode)create.path("candidate")).putObject("subject").put("kind","USER").put("userId","941012")
                .putNull("expectedPrincipalPublicId").putNull("expectedUserRowVersion");denied(create,Code.SUBJECT_INACTIVE);
        var revoke=revoke("R");((ObjectNode)revoke.path("candidate")).putObject("storedSubjectExpectation").put("kind","USER").put("userId","941012")
                .putNull("expectedPrincipalPublicId").putNull("expectedUserRowVersion");assertEquals("INACTIVE",assertInstanceOf(NativeUserFact.class,success(revoke).subject()).status());
        ((ObjectNode)create.path("candidate").path("subject")).put("userId","900009");
        var selfRead=success(create);assertEquals(PRINCIPAL.toString(),assertInstanceOf(NativeUserFact.class,selfRead.subject()).principalPublicId());
        assertEquals("AUTH_SCOPE_CHECKED_NOT_PEOPLE_COMMAND_SOD",selfRead.authPolicy().sodResult());
        assertEquals("NOT_PERFORMED_PEOPLE_NATIVE_REFETCH_REQUIRED",selfRead.peopleOwnerValidation());
    }
    @Test void nativeSupersededGraceAndStrictJwtExpiryAreNotDecoderSkewPermissions(){
        owner.update("UPDATE public.sys_auth_sessions SET superseded_at=?,superseded_expires_at=? WHERE session_id=?",
                Timestamp.from(now.minusSeconds(1)),Timestamp.from(now.plusSeconds(10)),session);
        assertEquals("ACTIVE_SUPERSEDED_GRACE",success(request("POLICY_LIST_AUTH_READ")).session().state());
        owner.update("UPDATE public.sys_auth_sessions SET superseded_expires_at=? WHERE session_id=?",Timestamp.from(now.minusSeconds(1)),session);
        denied(request("POLICY_LIST_AUTH_READ"),Code.ACTOR_JWT_INVALID);
        owner.update("UPDATE public.sys_auth_sessions SET superseded_at=NULL,superseded_expires_at=NULL WHERE session_id=?",session);
        token=new AuthSessionJwtTokenEncoder(JWT_SECRET,MAPPER).encode(new AuthSessionJwtTokenEncoder.SessionTokenClaims(
                ACTOR,TENANT,List.of("HR_ADMIN"),jti,family,now.minusSeconds(1),now,null,null,List.of()));
        denied(request("POLICY_LIST_AUTH_READ"),Code.ACTOR_JWT_INVALID);
    }
    @Configuration @EnableWebMvc @EnableWebSecurity @Import(WorkforcePolicyGovernanceInternalSecurityConfigV1.class)
    static class NativeHttpFixture{
        @Bean WorkforcePolicyGovernanceAuthorityControllerV1 controller(WorkforcePolicyGovernanceAuthorityAdapterV1 nativeAdapter){return new WorkforcePolicyGovernanceAuthorityControllerV1(nativeAdapter);}
    }
    @Test void exactHttpChainUsesActualCurrentNativeSessionAndRepeatedReadRefetchesAfterRevoke()throws Exception{
        try(var context=new AnnotationConfigWebApplicationContext()){
            context.setServletContext(new MockServletContext());context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test-only-s1-service-token",Map.of("dwp.auth.workforce-policy-governance-token","test-only-native-http-token")));
            context.addBeanFactoryPostProcessor(factory->{factory.registerSingleton("nativeAdapter",adapter);
                    factory.registerSingleton("jwtDecoder",decoder);factory.registerSingleton("objectMapper",MAPPER);});
            context.register(NativeHttpFixture.class);context.refresh();
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(org.springframework.security.web.FilterChainProxy.class)).build();
            String body=nativeRequest(request("POLICY_LIST_AUTH_READ")).toString();
            for(int index=0;index<2;index++)mvc.perform(post(PATH).contentType("application/json").content(body)
                    .header(WorkforcePolicyGovernanceInternalSecurityConfigV1.TOKEN_HEADER,"test-only-native-http-token")
                    .header(WorkforcePolicyGovernanceInternalSecurityConfigV1.IDENTITY_HEADER,"dwp-people-server")
                    .header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.boundary").value(BOUNDARY));
            owner.update("UPDATE public.sys_auth_sessions SET revoked_at=CURRENT_TIMESTAMP WHERE session_id=?",session);
            mvc.perform(post(PATH).content(body).header(WorkforcePolicyGovernanceInternalSecurityConfigV1.TOKEN_HEADER,"test-only-native-http-token")
                    .header(WorkforcePolicyGovernanceInternalSecurityConfigV1.IDENTITY_HEADER,"dwp-people-server")
                    .header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACTOR_JWT_INVALID"));
        }
    }
    enum NativeDenied { ACTOR_INACTIVE,TENANT_INACTIVE,ROW_STALE,ACCESS_STALE,PERSON_RELABEL,FOREIGN_TENANT,
        SESSION_REVOKED,SESSION_EXPIRED,DUTY_MISSING,SOD_MISSING,SCOPE_STALE,ROLE_INACTIVE,TARGET_FOREIGN,TARGET_STALE,DIGEST_WRONG }
    @ParameterizedTest @EnumSource(NativeDenied.class) void nativeChangesAreReadAgainNotCachedOrBodyApproved(NativeDenied row){
        ObjectNode n=request("POLICY_LIST_AUTH_READ");Code expected=Code.ACTOR_STALE;
        switch(row){
            case ACTOR_INACTIVE->{owner.update("UPDATE public.com_users SET status='INACTIVE' WHERE user_id=900009");expected=Code.ACTOR_INACTIVE;}
            case TENANT_INACTIVE->{owner.update("UPDATE public.com_tenants SET status='SUSPENDED' WHERE tenant_id=41");expected=Code.TENANT_INACTIVE;}
            case ROW_STALE,ACCESS_STALE,PERSON_RELABEL,FOREIGN_TENANT->{
                n.putObject("expectedActor").put("tenantId",row==NativeDenied.FOREIGN_TENANT?"42":"41").put("userId","900009")
                        .put("principalPublicId",PRINCIPAL.toString()).putNull("personPublicId").put("userRowVersion","0").put("accessRevision","0");
                if(row==NativeDenied.ROW_STALE)owner.update("UPDATE public.com_users SET version=1 WHERE user_id=900009");
                if(row==NativeDenied.ACCESS_STALE)owner.update("UPDATE public.com_users SET access_revision=1 WHERE user_id=900009");
                if(row==NativeDenied.PERSON_RELABEL){owner.update("UPDATE public.com_users SET person_public_id=? WHERE user_id=900009",UUID.randomUUID());expected=Code.ACTOR_LABEL_MISMATCH;}
                if(row==NativeDenied.FOREIGN_TENANT)expected=Code.ACTOR_LABEL_MISMATCH;
            }
            case SESSION_REVOKED->{owner.update("UPDATE public.sys_auth_sessions SET revoked_at=CURRENT_TIMESTAMP WHERE session_id=?",session);expected=Code.ACTOR_JWT_INVALID;}
            case SESSION_EXPIRED->{owner.update("UPDATE public.sys_auth_sessions SET idle_expires_at=? WHERE session_id=?",
                    Timestamp.from(now.minusSeconds(1)),session);expected=Code.ACTOR_JWT_INVALID;}
            case DUTY_MISSING->{owner.update("UPDATE public.sys_admin_scoped_duty_catalog SET lifecycle_state='RETIRED' WHERE duty_code=?",DUTY);expected=Code.DUTY_UNREGISTERED;}
            case SOD_MISSING->{owner.update("UPDATE public.sys_admin_scoped_duty_conflicts SET lifecycle_state='RETIRED' WHERE right_duty_code=?",DUTY);expected=Code.SOD_POLICY_UNREGISTERED;}
            case SCOPE_STALE->{((ObjectNode)n.path("governanceScope")).put("resourceSetVersion","99");expected=Code.SCOPE_MISMATCH;}
            case ROLE_INACTIVE->{n=create("HR_ADMIN");owner.update("UPDATE public.com_roles SET status='INACTIVE' WHERE role_id=?",nativeRole);expected=Code.ACTOR_JWT_INVALID;}
            case TARGET_FOREIGN,TARGET_STALE->{n=create("A");((ObjectNode)n.path("candidate")).putObject("subject").put("kind","USER")
                    .put("userId",row==NativeDenied.TARGET_FOREIGN?"999999":"941012").putNull("expectedPrincipalPublicId").put("expectedUserRowVersion","99");
                expected=row==NativeDenied.TARGET_FOREIGN?Code.SUBJECT_NOT_FOUND:Code.SUBJECT_STALE;}
            case DIGEST_WRONG->{n.put("expectedRequestDigest","a".repeat(64));expected=Code.BODY_DIGEST_MISMATCH;}
        }denied(n,expected);
    }
    @ParameterizedTest(name="secondsBeforeInjectedClock={0}") @ValueSource(longs={0,1})
    void alreadyExpiredSessionIsActorJwtInvalidAtBoundaryAndAfterDatabaseWallClockDelay(long secondsBefore){
        // The decoder owns an already-expired authentication session and maps it to
        // invalid_token. EVIDENCE_EXPIRED is reserved for post-decode TOCTOU/freshness.
        if(secondsBefore==1)owner.execute("SELECT pg_sleep(2)");
        owner.update("UPDATE public.sys_auth_sessions SET idle_expires_at=? WHERE session_id=?",
                Timestamp.from(now.minusSeconds(secondsBefore)),session);
        denied(request("POLICY_LIST_AUTH_READ"),Code.ACTOR_JWT_INVALID);
    }
    @Test void separateContextsFindNativeRowChangeEvenIfPublicRoleCodesDoNotChange(){
        var calls=new AtomicInteger();JwtDecoder changing=value->{
            if(calls.incrementAndGet()==2)owner.update("UPDATE public.com_users SET version=version+1 WHERE user_id=900009");return decoder.decode(value);};
        adapter=build(changing,identities);denied(request("POLICY_LIST_AUTH_READ"),Code.AUTH_CONTEXT_STALE);assertEquals(2,calls.get());
    }
    @Test void secondOwnerContextSourceChangesAreNotSameAuthRevisionAuthority(){
        var calls=new AtomicInteger();JwtDecoder changing=value->{
            if(calls.incrementAndGet()==2)owner.update("UPDATE public.com_admin_resource_sets SET updated_at=updated_at+interval '1 second' WHERE resource_set_id=?",set);return decoder.decode(value);};
        adapter=build(changing,identities);denied(request("POLICY_LIST_AUTH_READ"),Code.AUTH_CONTEXT_STALE);
    }
    @Test void ownerCallbackClockProgressionExpiryRegressionAndFailuresDoNotLeakPayload(){
        var calls=new AtomicInteger();JwtDecoder changing=value->{var jwt=decoder.decode(value);if(calls.incrementAndGet()==2)time.set(now.plusSeconds(30));return jwt;};
        adapter=build(changing,identities);denied(request("POLICY_LIST_AUTH_READ"),Code.EVIDENCE_EXPIRED);
        adapter=build(value->{throw new IllegalStateException("raw-secret-owner-jwt");},identities);
        var denied=assertInstanceOf(Failure.class,adapter.evaluate(bytes(nativeRequest(request("POLICY_LIST_AUTH_READ"))),token));
        assertEquals(Code.ACTOR_JWT_INVALID,denied.code());assertFalse(denied.toString().contains("raw-secret"));
        time.set(now);adapter=build(value->{var jwt=decoder.decode(value);time.set(now.minusSeconds(1));return jwt;},identities);
        denied(request("POLICY_LIST_AUTH_READ"),Code.CLOCK_REGRESSION);
    }
    @Test void nativeReadRoleCannotWriteCreateTempReadHistoryOrSetMigrationRole()throws Exception{
        assertTrue(jdbc.queryForObject("SELECT current_user=session_user AND current_user=?",Boolean.class,READ_ROLE));
        for(String sql:List.of("UPDATE public.com_users SET status='INACTIVE' WHERE user_id=900009","CREATE TABLE public.s1_forbidden(id bigint)",
                "CREATE TEMPORARY TABLE s1_forbidden(id bigint)","SELECT * FROM public.flyway_schema_history","SET ROLE "+POSTGRES.getUsername()))
            try(var c=runtime.getConnection();var s=c.createStatement()){var error=assertThrows(SQLException.class,()->s.execute(sql));assertEquals("42501",error.getSQLState());}
        owner.execute("REVOKE SELECT ON public.com_tenants FROM "+READ_ROLE);
        try{denied(request("POLICY_LIST_AUTH_READ"),Code.OWNER_SOURCE_UNAVAILABLE);}finally{owner.execute("GRANT SELECT ON public.com_tenants TO "+READ_ROLE);}
    }
    static PGSimpleDataSource source(String user,String password){
        var result=new PGSimpleDataSource();result.setServerNames(new String[]{POSTGRES.getHost()});result.setPortNumbers(new int[]{POSTGRES.getMappedPort(5432)});
        result.setDatabaseName(POSTGRES.getDatabaseName());result.setUser(user);result.setPassword(password);result.setConnectTimeout(5);result.setSocketTimeout(10);return result;
    }
}
