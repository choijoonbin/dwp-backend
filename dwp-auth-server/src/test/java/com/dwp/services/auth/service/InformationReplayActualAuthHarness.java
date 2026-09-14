package com.dwp.services.auth.service;

import com.dwp.services.auth.config.ProductAuthorizationSeedLoader;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.informationreplay.InformationReplayConfiguration;
import com.dwp.services.auth.informationreplay.InformationReplayEmbeddedServer;
import com.dwp.services.auth.informationreplay.InformationReplayAuthorityService;
import com.dwp.services.auth.repository.*;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real Auth/Redis/HTTP fixture. V8 is negative-only; V9 requires actual seed/validator approval, never invented descriptors. */
public final class InformationReplayActualAuthHarness implements AutoCloseable {
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private LettuceConnectionFactory connection;
    private InformationReplayEmbeddedServer http;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private long tenant, version;
    private String sealedChecksum;

    public InformationReplayActualAuthHarness(RSAKey owner, RSAKey transport, RSAKey attestation, long requiredRegistryVersion) throws Exception {
        if (!Set.of(8L, 9L).contains(requiredRegistryVersion)) throw new IllegalArgumentException("Only negative v8 or actual sealed v9 is supported.");
        try {
            postgres.start(); redisContainer.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var root = Files.isDirectory(Path.of("dwp-auth-server")) ? Path.of(".") : Path.of("..");
            var flyway = Flyway.configure().dataSource(source).locations("filesystem:" + root.resolve("dwp-auth-server/src/main/resources/db/migration"),
                    "filesystem:" + root.resolve("dwp-core/src/main/resources/db/migration")).load();
            flyway.migrate(); flyway.validate();
            if (flyway.info().pending().length != 0) throw new IllegalStateException("Fresh full Auth migrations remain pending.");
            connection = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379)); connection.afterPropertiesSet();
            redis = new StringRedisTemplate(connection);
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var unrelatedMfaKeys = generator.generateKeyPair();
            String prefix = "dwp.auth.approval-information-replay.";
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("replay-purpose-keys", Map.of(
                    prefix + "enabled", true, prefix + "owner-trusted-keys", new JWKSet(owner.toPublicJWK()).toString(),
                    prefix + "transport-trusted-keys", new JWKSet(transport.toPublicJWK()).toString(),
                    prefix + "attestation-private-key", attestation.toJSONString(),
                    prefix + "attestation-trusted-keys", new JWKSet(attestation.toPublicJWK()).toString())));
            context.registerBean(javax.sql.DataSource.class, () -> source); context.registerBean(KeyPair.class, () -> unrelatedMfaKeys);
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.registerBean(RoleMemberRepository.class, () -> new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(
                    context.getBean(jakarta.persistence.EntityManagerFactory.class))).getRepository(RoleMemberRepository.class));
            context.register(ProductSurfaceStepUpRuntimeSpringFixture.class, ProductAuthorizationAuthorityAdapter.class,
                    InformationReplayIdentityAuthorityBridge.class, InformationReplayConfiguration.class);
            context.refresh(); jdbc = context.getBean(JdbcTemplate.class);
            context.getBean(ProductAuthorizationSeedLoader.class).run(new DefaultApplicationArguments(new String[0]));
            var repository = context.getBean(ProductAuthorizationContractRepository.class);
            var bundle = repository.find("product-surfaces", requiredRegistryVersion).orElseThrow(
                    () -> new IllegalStateException("Actual sealed registry " + requiredRegistryVersion + " is absent; no fabricated release is allowed."));
            var seals = new StoredDescriptorSeal(jdbc, repository, context.getBean(ProductAuthorizationContractValidator.class),
                    context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class));
            seals.loadVersion(bundle);
            var contracts = context.getBean(ProductAuthorizationContractService.class);
            contracts.approve("product-surfaces", requiredRegistryVersion, "replay-isolated-checker");
            contracts.activate("product-surfaces", requiredRegistryVersion, "replay-isolated-release", 0);
            version = repository.findActive("product-surfaces").orElseThrow().version();
            if (version != requiredRegistryVersion) throw new IllegalStateException("Active registry pointer does not match the requested genuine release.");
            sealedChecksum = seals.loadActive(repository.findActive("product-surfaces").orElseThrow(),
                    repository.findActivePointer("product-surfaces").orElseThrow()).checksum();
            tenant = jdbc.queryForObject("SELECT min(tenant_id) FROM com_tenants WHERE status='ACTIVE'", Long.class);
            http = new InformationReplayEmbeddedServer(context.getBean(InformationReplayAuthorityService.class), true);
        } catch (Exception error) { close(); throw error; }
    }
    public long tenantId() { return tenant; }
    public long registryVersion() { return version; }
    public String sealedRegistryChecksum() { return sealedChecksum; }
    public JdbcTemplate jdbc() { return jdbc; }
    public StringRedisTemplate redis() { return redis; }
    public java.net.URI endpoint() { return http.endpoint(); }
    public InformationReplayAuthorityService service() { return context.getBean(InformationReplayAuthorityService.class); }
    public void subject(long user, UUID person) {
        jdbc.update("INSERT INTO com_users(user_id,tenant_id,display_name,email,status,identity_plane,person_public_id) VALUES(?,?,?,?,'ACTIVE','TENANT',?)",
                user, tenant, "Replay isolated subject", "replay-" + person + "@isolated.test", person);
    }
    public void workspace(long user) {
        long role = jdbc.queryForObject("SELECT role_id FROM com_roles WHERE tenant_id=? AND code='WORKSPACE_MEMBER'", Long.class, tenant);
        jdbc.update("INSERT INTO com_role_members(tenant_id,user_id,role_id) VALUES(?,?,?)", tenant, user, role);
    }
    public long role(String code, List<Long> users) {
        long role = jdbc.queryForObject("INSERT INTO com_roles(tenant_id,code,name,status) VALUES(?,?,?,'ACTIVE') RETURNING role_id", Long.class, tenant, code, "Replay isolated workflow role");
        jdbc.batchUpdate("INSERT INTO com_role_members(tenant_id,user_id,role_id) VALUES(?,?,?)", users.stream().map(user -> new Object[]{tenant, user, role}).toList());
        return role;
    }
    public PrincipalResourceGrantRepository.GrantRecord grant(long user, String resource, String permission, long grantor) {
        long resourceId = jdbc.queryForObject("SELECT resource_id FROM com_resources WHERE tenant_id=? AND key=? AND enabled", Long.class, tenant, resource);
        long permissionId = jdbc.queryForObject("SELECT permission_id FROM com_permissions WHERE code=?", Long.class, permission);
        return new PrincipalResourceGrantRepository(jdbc).grant(tenant, "USER", Long.toString(user), resourceId, permissionId, "ADMIN_DIRECT",
                "replay-" + UUID.randomUUID(), OffsetDateTime.now().plusMinutes(10), "Explicit disposable Replay authority fixture grant.", grantor);
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
