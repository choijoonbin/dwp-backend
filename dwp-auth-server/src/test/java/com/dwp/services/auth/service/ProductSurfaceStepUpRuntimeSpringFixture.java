package com.dwp.services.auth.service;

import com.dwp.services.auth.config.ProductAuthorizationSeedLoader;
import com.dwp.services.auth.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.security.KeyPair;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;

/** Only MFA/session and unrelated login collaborators are doubles; authority reads real repositories. */
@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
class ProductSurfaceStepUpRuntimeSpringFixture {
    @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
        var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
        factory.setPackagesToScan("com.dwp.services.auth.entity"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none")); return factory;
    }
    @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory, DataSource source) {
        var manager = new JpaTransactionManager(factory); manager.setDataSource(source); return manager;
    }
    @Bean TransactionTemplate transactions(JpaTransactionManager manager) { return new TransactionTemplate(manager); }
    @Bean AppGovernanceService governance(JdbcTemplate jdbc) { return new AppGovernanceService(jdbc, mock(IdentityAuditService.class)); }
    @Bean ScopedAdminDutyEvidenceService duties(JdbcTemplate jdbc) { return new ScopedAdminDutyEvidenceService(jdbc); }
    @Bean ScopedAdminDutyAssignmentService assignments(JdbcTemplate jdbc) { return new ScopedAdminDutyAssignmentService(jdbc); }
    @Bean AuthSessionService sessions() { return mock(AuthSessionService.class); }
    @Bean AuthService auth(EntityManagerFactory entityManager, JdbcTemplate jdbc, AppGovernanceService governance,
            ScopedAdminDutyEvidenceService duties, AuthSessionService sessions) {
        var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(entityManager));
        return new AuthService(repositories.getRepository(UserRepository.class), mock(UserAccountRepository.class),
                mock(TenantRepository.class), repositories.getRepository(RoleRepository.class),
                repositories.getRepository(RoleMemberRepository.class), mock(DirectoryGroupRepository.class),
                mock(DirectoryGroupMemberRepository.class), repositories.getRepository(RolePermissionRepository.class),
                repositories.getRepository(ResourceRepository.class), repositories.getRepository(PermissionRepository.class),
                new PrincipalResourceGrantRepository(jdbc), sessions, mock(AuthPolicyService.class),
                mock(IdentityAccountService.class), mock(LoginAttemptService.class), governance, duties, mock(PasswordEncoder.class));
    }
    @Bean ProductAuthorizationIdentityEvidenceService identity(AuthService auth, AppGovernanceService governance,
            ScopedAdminDutyEvidenceService duties) { return new ProductAuthorizationIdentityEvidenceService(auth, governance, duties); }
    @Bean ProductAuthorizationContractRepository registry(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new ProductAuthorizationContractRepository(jdbc, mapper);
    }
    @Bean ProductAuthorizationContractValidator validator(ObjectMapper mapper) { return new ProductAuthorizationContractValidator(mapper); }
    @Bean ProductAuthorizationContractService contracts(ProductAuthorizationContractRepository registry, ProductAuthorizationContractValidator validator) {
        return new ProductAuthorizationContractService(registry, validator);
    }
    @Bean ProductAuthorizationSeedLoader seeds(ProductAuthorizationContractValidator validator, ProductAuthorizationContractService contracts) {
        return new ProductAuthorizationSeedLoader(true, "classpath:product-authorization/product-surfaces-v1.index.generated.json",
                new DefaultResourceLoader(), validator, contracts);
    }
    @Bean ProductSurfaceAuthorityService authority(org.springframework.beans.factory.ObjectProvider<ProductSurfaceAuthorityPort> ports) {
        return new ProductSurfaceAuthorityService(ports);
    }
    @Bean ProductSurfaceStepUpRouteResolver resolver(ProductAuthorizationContractRepository registry,
            JdbcTemplate jdbc, ProductAuthorizationContractValidator validator, ObjectMapper mapper) {
        return new ProductSurfaceStepUpRouteResolver(registry, jdbc, validator, mapper);
    }
    @Bean ProductSurfaceStepUpRequestParser parser(ObjectMapper mapper) { return new ProductSurfaceStepUpRequestParser(mapper); }
    @Bean ProductSurfaceStepUpChallengeService challenges(ProductSurfaceStepUpRouteResolver resolver, ProductSurfaceAuthorityService authority,
            AuthSessionService sessions, ObjectMapper mapper, KeyPair keys) {
        return new ProductSurfaceStepUpChallengeService(resolver, authority, sessions, mock(OidcService.class),
                mock(StepUpBrowserBindingService.class), mapper, Clock.systemUTC(), keys.getPrivate(),
                "https://auth.corp.example.com/product-surface-step-up", "stepup-latest-seven", "urn:dwp:acr:mfa",
                Set.of("dwp-approval-server", "dwp-people-server"), 600, 300);
    }
}
