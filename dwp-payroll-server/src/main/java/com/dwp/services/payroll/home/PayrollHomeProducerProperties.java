package com.dwp.services.payroll.home;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("dwp.hris.payroll.home-producer")
public class PayrollHomeProducerProperties {

    private boolean enabled;
    private boolean eventPublicationEnabled;
    private String payloadReferenceSecret = "";
    private String signingKeyId = "";
    private String signingPublicKeyPem = "";
    private String workloadAuthentication = "CLIENT_CREDENTIAL_JWT";
    private Duration freshFor = Duration.ofMinutes(2);
    private Duration staleAfter = Duration.ofMinutes(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEventPublicationEnabled() {
        return eventPublicationEnabled;
    }

    public void setEventPublicationEnabled(boolean eventPublicationEnabled) {
        this.eventPublicationEnabled = eventPublicationEnabled;
    }

    public String getPayloadReferenceSecret() {
        return payloadReferenceSecret;
    }

    public void setPayloadReferenceSecret(String payloadReferenceSecret) {
        this.payloadReferenceSecret = payloadReferenceSecret;
    }

    public Duration getFreshFor() {
        return freshFor;
    }

    public void setFreshFor(Duration freshFor) {
        this.freshFor = freshFor;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    public String getSigningKeyId() {
        return signingKeyId;
    }

    public void setSigningKeyId(String signingKeyId) {
        this.signingKeyId = signingKeyId;
    }

    public String getSigningPublicKeyPem() {
        return signingPublicKeyPem;
    }

    public void setSigningPublicKeyPem(String signingPublicKeyPem) {
        this.signingPublicKeyPem = signingPublicKeyPem;
    }

    public String getWorkloadAuthentication() {
        return workloadAuthentication;
    }

    public void setWorkloadAuthentication(String workloadAuthentication) {
        this.workloadAuthentication = workloadAuthentication;
    }

    void validateForActivation() {
        if (!enabled) return;
        if (payloadReferenceSecret == null
                || payloadReferenceSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "Payroll Home payload-reference secret must contain at least 32 UTF-8 bytes.");
        }
        if (signingKeyId == null || !signingKeyId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$")
                || signingPublicKeyPem == null || signingPublicKeyPem.isBlank()) {
            throw new IllegalStateException(
                    "Payroll Home Platform signing key id and public key are required.");
        }
        try {
            com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1
                    .WorkloadAuthentication.valueOf(workloadAuthentication);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Payroll Home workload authentication profile is invalid.", exception);
        }
        if (freshFor == null || freshFor.isNegative() || freshFor.isZero()
                || staleAfter == null || staleAfter.compareTo(freshFor) <= 0) {
            throw new IllegalStateException(
                    "Payroll Home freshness durations must be positive and ordered.");
        }
    }
}
