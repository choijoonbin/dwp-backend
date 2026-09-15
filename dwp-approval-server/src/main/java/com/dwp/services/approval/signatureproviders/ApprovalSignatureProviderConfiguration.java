package com.dwp.services.approval.signatureproviders;

import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dwp.approval",
        name = "external-signature-enabled", havingValue = "true")
public class ApprovalSignatureProviderConfiguration {
    @Bean
    SignatureProviderCanonical approvalSignatureProviderCanonical(ObjectMapper mapper) {
        return new SignatureProviderCanonical(mapper);
    }

    @Bean
    SignatureProviderPolicyCompiler approvalSignatureProviderPolicyCompiler(ObjectMapper mapper) {
        return new SignatureProviderPolicyCompiler(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(SignatureProviderRuntime.class)
    SignatureProviderRuntime unavailableApprovalSignatureProviderRuntime() {
        return new UnavailableSignatureProviderRuntime();
    }

    @Bean
    SignatureProviderCurrentAuthority approvalSignatureProviderAuthority(
            ApprovalWorkAuthority workAuthority) {
        return new ApprovalSignatureProviderAuthority(workAuthority);
    }

    @Bean
    ApprovalSignatureProviderHighGuard approvalSignatureProviderHighGuard(
            ApprovalStepUpVerifier verifier, ApprovalStepUpReplayRepository replay) {
        return new ApprovalSignatureProviderHighGuard(verifier, replay);
    }

    @Bean
    SignatureProviderPersistence approvalSignatureProviderPersistence(
            NamedParameterJdbcTemplate jdbc, SignatureProviderCanonical canonical) {
        return new SignatureProviderPersistence(jdbc, canonical, Clock.systemUTC());
    }

    @Bean
    SignatureProviderPolicyRepository approvalSignatureProviderPolicyRepository(
            SignatureProviderPersistence persistence, SignatureProviderPolicyCompiler compiler) {
        return new SignatureProviderPolicyRepository(persistence, compiler);
    }

    @Bean
    SignatureProviderDiagnosticsRepository approvalSignatureProviderDiagnosticsRepository(
            SignatureProviderPersistence persistence) {
        return new SignatureProviderDiagnosticsRepository(persistence);
    }

    @Bean
    ExternalSignatureRepository approvalExternalSignatureRepository(
            SignatureProviderPersistence persistence) {
        return new ExternalSignatureRepository(persistence);
    }

    @Bean
    SignatureProviderProjection approvalSignatureProviderProjection(
            SignatureProviderPersistence persistence, SignatureProviderPolicyRepository policies,
            SignatureProviderDiagnosticsRepository diagnostics, SignatureProviderRuntime runtime) {
        return new SignatureProviderProjection(persistence, policies, diagnostics, runtime);
    }

    @Bean
    ApprovalSignatureProviderService approvalSignatureProviderService(
            SignatureProviderCurrentAuthority authority, ApprovalSignatureProviderHighGuard high,
            SignatureProviderPersistence persistence, SignatureProviderPolicyRepository policies,
            SignatureProviderDiagnosticsRepository diagnostics, SignatureProviderProjection projection,
            SignatureProviderPolicyCompiler compiler, SignatureProviderRuntime runtime) {
        return new ApprovalSignatureProviderService(authority, high, persistence, policies,
                diagnostics, projection, compiler, runtime);
    }

    @Bean
    ApprovalExternalSignatureService approvalExternalSignatureService(
            SignatureProviderCurrentAuthority authority, ApprovalSignatureProviderHighGuard high,
            SignatureProviderPersistence persistence, SignatureProviderPolicyRepository policies,
            SignatureProviderProjection projection, ExternalSignatureRepository requests,
            SignatureProviderRuntime runtime) {
        return new ApprovalExternalSignatureService(authority, high, persistence, policies,
                projection, requests, runtime);
    }
}
