package com.dwp.services.auth.service;

import com.dwp.services.auth.config.ProductAuthorizationSeedLoader;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
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

/** Joint dual-service test fixture. Catalog import is not authority; every user/role/grant is explicit and disposable. */
public final class WorkflowRuntimeActualAuthHarness implements AutoCloseable {
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private LettuceConnectionFactory connection;
    private WorkflowRuntimeEmbeddedServer http;
    private WorkflowRuntimeAuthorityService runtime;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private long tenant;
    public WorkflowRuntimeActualAuthHarness(RSAKey owner, RSAKey transport, RSAKey attestation) throws Exception {
        try {
            postgres.start(); redisContainer.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var root = Files.isDirectory(Path.of("dwp-auth-server")) ? Path.of(".") : Path.of("..");
            var flyway = Flyway.configure().dataSource(source).locations("filesystem:" + root.resolve("dwp-auth-server/src/main/resources/db/migration"),
                    "filesystem:" + root.resolve("dwp-core/src/main/resources/db/migration")).load(); flyway.migrate(); flyway.validate();
            if (flyway.info().pending().length != 0) throw new IllegalStateException("Fresh full Auth migrations remain pending.");
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var unrelatedMfaKeys = generator.generateKeyPair();
            context.registerBean(javax.sql.DataSource.class, () -> source); context.registerBean(KeyPair.class, () -> unrelatedMfaKeys);
            context.register(ProductSurfaceStepUpRuntimeSpringFixture.class, ProductAuthorizationAuthorityAdapter.class, WorkflowRuntimeIdentityAuthorityBridge.class);
            context.refresh(); jdbc = context.getBean(JdbcTemplate.class);
            context.getBean(ProductAuthorizationSeedLoader.class).run(new DefaultApplicationArguments(new String[0]));
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve("product-surfaces", 8, "joint-isolated-checker"); contracts.activate("product-surfaces", 8, "joint-isolated-release", 0);
            tenant = jdbc.queryForObject("SELECT min(tenant_id) FROM com_tenants WHERE status='ACTIVE'", Long.class);
            var mapper = new WorkflowRuntimeJson(new ObjectMapper().findAndRegisterModules());
            var keys = new WorkflowRuntimeKeys(mapper, new JWKSet(owner.toPublicJWK()).toString(), new JWKSet(transport.toPublicJWK()).toString(),
                    attestation.toJSONString(), new JWKSet(attestation.toPublicJWK()).toString());
            var verifier = new WorkflowRuntimeProofVerifier(mapper, keys);
            var factory = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(context.getBean(jakarta.persistence.EntityManagerFactory.class)));
            var sources = new WorkflowRuntimeSourceRepository(new NamedParameterJdbcTemplate(source), factory.getRepository(RoleMemberRepository.class), mapper);
            connection = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379)); connection.afterPropertiesSet();
            redis = new StringRedisTemplate(connection);
            runtime = new WorkflowRuntimeAuthorityService(verifier, context.getBean(WorkflowRuntimeIdentityAuthorityBridge.class), new WorkflowRuntimeAdmissionLink(verifier, keys),
                    sources, new WorkflowRuntimeReplayStore(redis), new WorkflowRuntimeAttestationIssuer(mapper, keys), mapper);
            http = new WorkflowRuntimeEmbeddedServer(runtime, true);
        } catch (Exception exception) { close(); throw exception; }
    }
    public long tenantId() { return tenant; }
    public JdbcTemplate jdbc() { return jdbc; }
    public StringRedisTemplate redis() { return redis; }
    public java.net.URI endpoint() { return http.endpoint(); }
    public WorkflowRuntimeAuthorityService runtime() { return runtime; }
    public void subject(long user, UUID person) {
        jdbc.update("INSERT INTO com_users(user_id,tenant_id,display_name,email,status,identity_plane,person_public_id) VALUES(?,?,?,?,'ACTIVE','TENANT',?)",
                user, tenant, "Joint isolated subject", "joint-" + person + "@isolated.test", person);
    }
    public void workspace(long user) {
        long role = jdbc.queryForObject("SELECT role_id FROM com_roles WHERE tenant_id=? AND code='WORKSPACE_MEMBER'", Long.class, tenant);
        jdbc.update("INSERT INTO com_role_members(tenant_id,user_id,role_id) VALUES(?,?,?)", tenant, user, role);
    }
    public long role(String code, List<Long> users) {
        long role = jdbc.queryForObject("INSERT INTO com_roles(tenant_id,code,name,status) VALUES(?,?,?,'ACTIVE') RETURNING role_id", Long.class, tenant, code, "Joint isolated workflow role");
        jdbc.batchUpdate("INSERT INTO com_role_members(tenant_id,user_id,role_id) VALUES(?,?,?)", users.stream().map(user -> new Object[] {tenant, user, role}).toList());
        return role;
    }
    public PrincipalResourceGrantRepository.GrantRecord grant(long user, String resource, String permission, long grantor) {
        long resourceId = jdbc.queryForObject("SELECT resource_id FROM com_resources WHERE tenant_id=? AND key=? AND enabled", Long.class, tenant, resource);
        long permissionId = jdbc.queryForObject("SELECT permission_id FROM com_permissions WHERE code=?", Long.class, permission);
        return new PrincipalResourceGrantRepository(jdbc).grant(tenant, "USER", Long.toString(user), resourceId, permissionId, "ADMIN_DIRECT",
                "joint-" + UUID.randomUUID(), OffsetDateTime.now().plusMinutes(10), "Explicit joint isolated authority fixture grant.", grantor);
    }
    public ProductSurfaceAuthorityDtos.AuthorityResult current(long user, String route) {
        return context.getBean(ProductSurfaceAuthorityService.class).evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(tenant, user,
                "approvals", "approvals.work", ProductSurfaceAuthorityDtos.AccessMode.NORMAL, route, null, null, null, null, List.of()));
    }
    @Override public void close() {
        if (http != null) http.close(); if (context.isActive()) context.close(); if (connection != null) connection.destroy();
        redisContainer.close(); postgres.close();
    }
}
