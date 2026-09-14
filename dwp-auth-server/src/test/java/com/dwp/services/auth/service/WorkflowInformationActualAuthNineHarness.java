package com.dwp.services.auth.service;

import com.dwp.services.auth.config.ProductAuthorizationSeedLoader;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.informationreplay.*;
import com.dwp.services.auth.repository.*;
import com.dwp.services.auth.workflowruntime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One genuine9 disposable Auth DB and Redis, two installed HTTP chains, six disjoint purpose keys. */
public final class WorkflowInformationActualAuthNineHarness implements AutoCloseable {
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private LettuceConnectionFactory connection;
    private WorkflowRuntimeEmbeddedServer runtimeHttp;
    private InformationReplayEmbeddedServer replayHttp;
    private WorkflowRuntimeAuthorityService runtime;
    private InformationReplayAuthorityService replay;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private long tenant;
    private String checksum;

    public WorkflowInformationActualAuthNineHarness(RSAKey runtimeOwner, RSAKey runtimeTransport, RSAKey runtimeAttestation,
            RSAKey replayOwner, RSAKey replayTransport, RSAKey replayAttestation) throws Exception {
        var keys = List.of(runtimeOwner, runtimeTransport, runtimeAttestation, replayOwner, replayTransport, replayAttestation);
        var thumbprints = new java.util.HashSet<String>();
        var identifiers = new java.util.HashSet<String>();
        for (var key : keys) if (!thumbprints.add(key.computeThumbprint().toString()) || !identifiers.add(key.getKeyID()))
            throw new IllegalArgumentException("Runtime and Replay require six disjoint purpose keys.");
        try {
            postgres.start(); redisContainer.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var root = Files.isDirectory(Path.of("dwp-auth-server")) ? Path.of(".") : Path.of("..");
            var flyway = Flyway.configure().dataSource(source).locations("filesystem:" + root.resolve("dwp-auth-server/src/main/resources/db/migration"),
                    "filesystem:" + root.resolve("dwp-core/src/main/resources/db/migration")).load();
            flyway.migrate(); flyway.validate();
            if (flyway.info().pending().length != 0) throw new IllegalStateException("Fresh full Auth migrations remain pending.");
            connection = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
            connection.afterPropertiesSet(); redis = new StringRedisTemplate(connection);
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var unrelatedMfaKeys = generator.generateKeyPair();
            context.registerBean(javax.sql.DataSource.class, () -> source);
            context.registerBean(KeyPair.class, () -> unrelatedMfaKeys);
            context.registerBean(RoleMemberRepository.class, () -> new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(
                    context.getBean(jakarta.persistence.EntityManagerFactory.class))).getRepository(RoleMemberRepository.class));
            context.register(ProductSurfaceStepUpRuntimeSpringFixture.class, ProductAuthorizationAuthorityAdapter.class,
                    WorkflowRuntimeIdentityAuthorityBridge.class, InformationReplayIdentityAuthorityBridge.class);
            context.refresh(); jdbc = context.getBean(JdbcTemplate.class);
            context.getBean(ProductAuthorizationSeedLoader.class).run(new DefaultApplicationArguments(new String[0]));
            var repository = context.getBean(ProductAuthorizationContractRepository.class);
            var validator = context.getBean(ProductAuthorizationContractValidator.class);
            var mapper = context.getBean(ObjectMapper.class);
            var seal = new StoredDescriptorSeal(jdbc, repository, validator, mapper);
            seal.loadVersion(repository.find("product-surfaces", 9).orElseThrow());
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve("product-surfaces", 9, "combined-isolated-checker");
            contracts.activate("product-surfaces", 9, "combined-isolated-release", 0);
            checksum = seal.loadActive(repository.findActive("product-surfaces").orElseThrow(),
                    repository.findActivePointer("product-surfaces").orElseThrow()).checksum();
            if (!"02b19c4119e560b63d4054ec317fe7e4d694e402a5af03960c63b20db4b41ab7".equals(checksum))
                throw new IllegalStateException("Combined fixture requires exact sealed9, never an older or fabricated release.");
            tenant = jdbc.queryForObject("SELECT min(tenant_id) FROM com_tenants WHERE status='ACTIVE'", Long.class);
            var runtimeJson = new WorkflowRuntimeJson(mapper);
            var runtimeKeys = new WorkflowRuntimeKeys(runtimeJson, publicKeys(runtimeOwner), publicKeys(runtimeTransport),
                    runtimeAttestation.toJSONString(), publicKeys(runtimeAttestation));
            var runtimeVerifier = new WorkflowRuntimeProofVerifier(runtimeJson, runtimeKeys);
            var sources = new WorkflowRuntimeSourceRepository(new NamedParameterJdbcTemplate(jdbc), context.getBean(RoleMemberRepository.class), runtimeJson);
            runtime = new WorkflowRuntimeAuthorityService(runtimeVerifier, context.getBean(WorkflowRuntimeIdentityAuthorityBridge.class),
                    new WorkflowRuntimeAdmissionLink(runtimeVerifier, runtimeKeys), sources, new WorkflowRuntimeReplayStore(redis),
                    new WorkflowRuntimeAttestationIssuer(runtimeJson, runtimeKeys), runtimeJson);
            var replayJson = new InformationReplayJson(mapper);
            var replayKeys = new InformationReplayKeys(replayJson, publicKeys(replayOwner), publicKeys(replayTransport),
                    replayAttestation.toJSONString(), publicKeys(replayAttestation));
            replay = new InformationReplayAuthorityService(true, new InformationReplayProofVerifier(replayJson, replayKeys, java.time.Clock.systemUTC()),
                    context.getBean(InformationReplayIdentityAuthorityBridge.class), new InformationReplayReplayStore(redis),
                    new InformationReplayAttestationIssuer(replayKeys, replayJson), replayJson);
            runtimeHttp = new WorkflowRuntimeEmbeddedServer(runtime, true);
            replayHttp = new InformationReplayEmbeddedServer(replay, true);
        } catch (Exception failure) { close(); throw failure; }
    }
    private static String publicKeys(RSAKey key) { return new JWKSet(key.toPublicJWK()).toString(); }
    public long tenantId() { return tenant; }
    public long registryVersion() { return 9; }
    public String sealedRegistryChecksum() { return checksum; }
    public JdbcTemplate jdbc() { return jdbc; }
    public StringRedisTemplate redis() { return redis; }
    public java.net.URI runtimeEndpoint() { return runtimeHttp.endpoint(); }
    public java.net.URI replayEndpoint() { return replayHttp.endpoint(); }
    public WorkflowRuntimeAuthorityService runtime() { return runtime; }
    public InformationReplayAuthorityService replay() { return replay; }
    public void subject(long user, UUID person) {
        jdbc.update("INSERT INTO com_users(user_id,tenant_id,display_name,email,status,identity_plane,person_public_id) VALUES(?,?,?,?,'ACTIVE','TENANT',?)",
                user, tenant, "Combined isolated subject", "combined-" + person + "@isolated.test", person);
    }
    public void workspace(long user) {
        long role = jdbc.queryForObject("SELECT role_id FROM com_roles WHERE tenant_id=? AND code='WORKSPACE_MEMBER'", Long.class, tenant);
        jdbc.update("INSERT INTO com_role_members(tenant_id,user_id,role_id) VALUES(?,?,?)", tenant, user, role);
    }
    public long role(String code, List<Long> users) {
        long role = jdbc.queryForObject("INSERT INTO com_roles(tenant_id,code,name,status) VALUES(?,?,?,'ACTIVE') RETURNING role_id", Long.class, tenant, code, "Combined isolated workflow role");
        jdbc.batchUpdate("INSERT INTO com_role_members(tenant_id,user_id,role_id) VALUES(?,?,?)", users.stream().map(user -> new Object[]{tenant, user, role}).toList());
        return role;
    }
    public PrincipalResourceGrantRepository.GrantRecord grant(long user, String resource, String permission, long grantor) {
        long resourceId = jdbc.queryForObject("SELECT resource_id FROM com_resources WHERE tenant_id=? AND key=? AND enabled", Long.class, tenant, resource);
        long permissionId = jdbc.queryForObject("SELECT permission_id FROM com_permissions WHERE code=?", Long.class, permission);
        return new PrincipalResourceGrantRepository(jdbc).grant(tenant, "USER", Long.toString(user), resourceId, permissionId,
                "ADMIN_DIRECT", "combined-" + UUID.randomUUID(), OffsetDateTime.now().plusMinutes(10),
                "Explicit disposable combined9 authority grant; catalog import is not authority.", grantor);
    }
    public ProductSurfaceAuthorityDtos.AuthorityResult current(long user, String route) {
        return context.getBean(ProductSurfaceAuthorityService.class).evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant, user,
                "approvals", "approvals.work", ProductSurfaceAuthorityDtos.AccessMode.NORMAL, route, null, null, null, null, List.of()));
    }
    @Override public void close() {
        if (replayHttp != null) replayHttp.close(); if (runtimeHttp != null) runtimeHttp.close();
        if (context.isActive()) context.close(); if (connection != null) connection.destroy();
        redisContainer.close(); postgres.close();
    }
}
