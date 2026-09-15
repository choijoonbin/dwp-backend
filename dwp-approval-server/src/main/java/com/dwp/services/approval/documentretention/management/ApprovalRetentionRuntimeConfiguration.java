package com.dwp.services.approval.documentretention.management;

import com.dwp.services.approval.documentretention.ApprovalRetentionObjectJobs;
import com.dwp.services.approval.documentretention.ApprovalRetentionObjectWorker;
import com.dwp.services.approval.documentretention.ApprovalRetentionS3Storage;
import com.dwp.services.approval.documentretention.ApprovalRetentionStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/** Explicit opt-in production wiring for the dedicated managed-retention state machine. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="dwp.approval.retention-worker",name="enabled",havingValue="true")
public class ApprovalRetentionRuntimeConfiguration {
    static final String PREFIX="dwp.approval.retention-worker.";

    @Bean(name="approvalRetentionExecutorDataSource",destroyMethod="close")
    @ConditionalOnMissingBean(name="approvalRetentionExecutorDataSource")
    DataSource approvalRetentionExecutorDataSource(Environment environment) {
        String jdbcUrl=required(environment,PREFIX+"executor.jdbc-url",2048);
        String username=required(environment,PREFIX+"executor.username",128);
        String password=required(environment,PREFIX+"executor.password",1024);
        if(!jdbcUrl.startsWith("jdbc:postgresql://") || !username.matches("[A-Za-z_][A-Za-z0-9_-]{2,127}"))
            throw new IllegalArgumentException("A dedicated PostgreSQL retention executor is required");
        var config=new HikariConfig();config.setJdbcUrl(jdbcUrl);config.setUsername(username);config.setPassword(password);
        config.setPoolName("approval-retention-executor");config.setMaximumPoolSize(2);config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);config.setValidationTimeout(3000);config.setIdleTimeout(60000);
        config.setMaxLifetime(600000);config.setAutoCommit(true);config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean
    @DependsOn("flyway")
    @ConditionalOnMissingBean(ApprovalRetentionManagedExecutionRepository.class)
    ApprovalRetentionManagedExecutionRepository approvalRetentionManagedExecutionRepository(
            @Qualifier("approvalRetentionExecutorDataSource") DataSource dataSource) {
        Boolean dedicated=new JdbcTemplate(dataSource).queryForObject("""
            SELECT COALESCE((SELECT pg_has_role(session_user,'dwp_approval_retention_executor','MEMBER')
                AND NOT pg_has_role(session_user,'dwp_approval_retention_owner','MEMBER')
                AND NOT rolsuper AND NOT rolbypassrls
                FROM pg_roles WHERE rolname=session_user),false)
            """,Boolean.class);
        if(!Boolean.TRUE.equals(dedicated)) throw new IllegalStateException("Dedicated retention executor role is not active");
        return new ApprovalRetentionManagedExecutionRepository(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalRetentionStorage.class)
    ApprovalRetentionStorage approvalRetentionStorage(ObjectProvider<S3Client> clients,Environment environment) {
        S3Client client=clients.getIfUnique();
        String bucket=environment.getProperty("dwp.approval.attachments.s3.bucket","");
        String prefix=environment.getProperty("dwp.approval.attachments.s3.prefix","dwp-approval/quarantine");
        if(client==null || bucket.isBlank() || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || !prefix.matches("[a-z0-9][a-z0-9/_-]{0,159}") || prefix.contains(".."))
            return new MissingStorage();
        return new ApprovalRetentionS3Storage(client,bucket,prefix);
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalRetentionExecutionAuthorityPort.class)
    ApprovalRetentionExecutionAuthorityPort approvalRetentionExecutionAuthorityPort(NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,Environment environment) {
        if(!strictBoolean(environment,PREFIX+"authority.enabled",false)) return new MissingAuthority();
        try {
            String base=required(environment,PREFIX+"authority.auth-base-url",2000);
            if(base.endsWith("/")) throw ApprovalRetentionErrors.unavailable();
            RSAKey owner=privateRsa(required(environment,PREFIX+"authority.owner-private-jwk",65536));
            RSAKey transport=privateRsa(required(environment,PREFIX+"authority.transport-private-jwk",65536));
            return new AuthApprovalRetentionExecutionAuthorityPort(jdbc,mapper,Clock.systemUTC(),
                    URI.create(base+AuthApprovalRetentionExecutionAuthorityPort.PATH),owner,transport);
        } catch(com.dwp.core.exception.BaseException failure) {throw failure;}
        catch(RuntimeException invalid) {throw new IllegalArgumentException("Invalid retention authority configuration",invalid);}
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalRetentionExecutionVerifier.class)
    ApprovalRetentionExecutionVerifier approvalRetentionExecutionVerifier(ObjectMapper mapper,Environment environment,
            ApprovalRetentionExecutionAuthorityPort authority) {
        if(!authority.configured()) return ApprovalRetentionExecutionVerifier.notConfigured(mapper,Clock.systemUTC());
        try {
            PublicKey key=publicEd25519(required(environment,PREFIX+"authority.execution-public-key-x509-base64",16384));
            String issuer=required(environment,PREFIX+"authority.execution-issuer",160);
            String keyId=required(environment,PREFIX+"authority.execution-key-id",80);
            if(!issuer.matches("[A-Za-z0-9._:-]{1,160}") || !keyId.matches("[A-Za-z0-9._:-]{1,80}"))
                throw new IllegalArgumentException("Pinned execution issuer and key id required");
            return new ApprovalRetentionExecutionVerifier(mapper,Clock.systemUTC(),key,issuer,keyId);
        } catch(RuntimeException invalid) {throw new IllegalArgumentException("Invalid retention execution verifier configuration",invalid);}
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalRetentionObjectJobs.class)
    ApprovalRetentionObjectJobs approvalRetentionObjectJobs(
            @Qualifier("approvalRetentionExecutorDataSource") DataSource dataSource) {
        return new ApprovalRetentionObjectJobs(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalRetentionObjectWorker.class)
    ApprovalRetentionObjectWorker approvalRetentionObjectWorker(ApprovalRetentionObjectJobs jobs,
            ApprovalRetentionStorage storage) {
        return new ApprovalRetentionObjectWorker(jobs,storage);
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalRetentionIntentExecutor.class)
    ApprovalRetentionIntentExecutor approvalRetentionIntentExecutor(
            @Qualifier("approvalRetentionExecutorDataSource") DataSource dataSource,
            ApprovalRetentionExecutionAuthorityPort authority,ApprovalRetentionExecutionVerifier verifier) {
        return new ApprovalRetentionIntentExecutor(dataSource,authority,verifier);
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalRetentionManagedWorker.class)
    ApprovalRetentionManagedWorker approvalRetentionManagedWorker(ApprovalRetentionManagedExecutionRepository executions,
            ApprovalRetentionExecutionAuthorityPort authority,ApprovalRetentionExecutionVerifier verifier,
            ApprovalRetentionObjectWorker objects,ApprovalRetentionForeignJournal foreign,
            ObjectProvider<ApprovalRetentionForeignPort> supplied,Environment environment) {
        var ports=new ArrayList<>(supplied.orderedStream().toList());
        addMissing(ports,"AUDIT");addMissing(ports,"NOTIFICATION");
        String workerId=workerId(environment.getProperty(PREFIX+"worker-id",""),
                environment.getProperty("dwp.audit.service-instance",environment.getProperty("HOSTNAME","local")));
        int leaseSeconds=boundedInt(environment,PREFIX+"lease-seconds",90,30,300);
        return new ApprovalRetentionManagedWorker(executions,authority,verifier,objects,foreign,ports,
                workerId,Duration.ofSeconds(leaseSeconds));
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalRetentionManagedSchedule.class)
    ApprovalRetentionManagedSchedule approvalRetentionManagedSchedule(ApprovalRetentionManagedWorker worker,
            Environment environment) {
        int batch=boundedInt(environment,PREFIX+"batch-size",25,1,100);
        boundedInt(environment,PREFIX+"poll-delay-ms",2000,250,60000);
        boundedInt(environment,PREFIX+"initial-delay-ms",10000,0,300000);
        return new ApprovalRetentionManagedSchedule(worker,batch);
    }

    private static void addMissing(Collection<ApprovalRetentionForeignPort> ports,String service) {
        if(ports.stream().noneMatch(port->service.equals(port.consumerService()))) ports.add(new MissingForeign(service));
    }

    static int boundedInt(Environment environment,String property,int fallback,int minimum,int maximum) {
        String raw=environment.getProperty(property);
        if(raw==null) return fallback;
        if(!raw.matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException("Invalid positive retention worker property: "+property);
        try {
            int value=Integer.parseInt(raw);
            if(value<minimum || value>maximum) throw new IllegalArgumentException("Retention worker property is outside its closed bound: "+property);
            return value;
        } catch(NumberFormatException invalid) {throw new IllegalArgumentException("Invalid retention worker property: "+property,invalid);}
    }

    static boolean strictBoolean(Environment environment,String property,boolean fallback) {
        String raw=environment.getProperty(property);
        if(raw==null) return fallback;
        if(!Set.of("true","false").contains(raw.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Invalid retention worker boolean: "+property);
        return Boolean.parseBoolean(raw);
    }

    private static String required(Environment environment,String property,int maximum) {
        String value=environment.getProperty(property);
        if(value==null || value.isBlank() || value.length()>maximum
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Required retention worker property is unavailable: "+property);
        return value;
    }

    private static RSAKey privateRsa(String raw) {
        try {
            var parsed=JWK.parse(raw);
            if(!(parsed instanceof RSAKey key) || !key.isPrivate()) throw new IllegalArgumentException("Private RSA JWK required");
            return key;
        } catch(java.text.ParseException invalid) {throw new IllegalArgumentException("Invalid retention RSA JWK",invalid);}
    }

    private static PublicKey publicEd25519(String raw) {
        try {
            String value=raw.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(value)));
        } catch(Exception invalid) {throw new IllegalArgumentException("Invalid retention Ed25519 public key",invalid);}
    }

    private static String workerId(String configured,String instance) {
        if(configured!=null && !configured.isBlank()) {
            if(!configured.matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,119}"))
                throw new IllegalArgumentException("Closed retention worker id required");
            return configured;
        }
        String normalized=(instance==null?"local":instance).replaceAll("[^A-Za-z0-9._:-]","_");
        if(normalized.isBlank()) normalized="local";
        if(normalized.length()>60) normalized=normalized.substring(0,60);
        return "approval-retention-"+normalized+'-'+UUID.randomUUID();
    }

    private static final class MissingAuthority implements ApprovalRetentionExecutionAuthorityPort {
        @Override public boolean configured() {return false;}
        @Override public SignedAuthorization current(Target target) {
            throw ApprovalRetentionErrors.dependencyNotConfigured("AUTHORITY_PORT_NOT_CONFIGURED");
        }
    }

    private record MissingForeign(String consumerService) implements ApprovalRetentionForeignPort {
        private MissingForeign {
            if(!Set.of("AUDIT","NOTIFICATION").contains(consumerService)) throw new IllegalArgumentException("Unknown retention owner");
        }
        @Override public boolean configured() {return false;}
        @Override public ApprovalRetentionForeignDtos.SignedAck deleteDeclaredCopies(ApprovalRetentionForeignDtos.DeletionRequest request) {
            throw ApprovalRetentionErrors.dependencyNotConfigured(consumerService+"_OWNER_NOT_CONFIGURED");
        }
        @Override public ApprovalRetentionForeignDtos.SignedAck reconcileDeclaredCopies(ApprovalRetentionForeignDtos.DeletionRequest request) {
            throw ApprovalRetentionErrors.dependencyNotConfigured(consumerService+"_OWNER_NOT_CONFIGURED");
        }
    }

    private static final class MissingStorage implements ApprovalRetentionStorage {
        @Override public boolean configured() {return false;}
        @Override public String locatorSha256() {
            throw ApprovalRetentionErrors.dependencyNotConfigured("OBJECT_STORAGE_NOT_CONFIGURED");
        }
        @Override public com.dwp.services.approval.attachment.ApprovalAttachmentStorage.Stored reconcile(String key,long size,String sha256) {
            throw ApprovalRetentionErrors.dependencyNotConfigured("OBJECT_STORAGE_NOT_CONFIGURED");
        }
        @Override public void verifyPresence(com.dwp.services.approval.attachment.ApprovalAttachmentStorage.Stored stored) {
            throw ApprovalRetentionErrors.dependencyNotConfigured("OBJECT_STORAGE_NOT_CONFIGURED");
        }
        @Override public boolean deleteAndConfirmAbsent(com.dwp.services.approval.attachment.ApprovalAttachmentStorage.Stored stored) {
            throw ApprovalRetentionErrors.dependencyNotConfigured("OBJECT_STORAGE_NOT_CONFIGURED");
        }
    }
}
