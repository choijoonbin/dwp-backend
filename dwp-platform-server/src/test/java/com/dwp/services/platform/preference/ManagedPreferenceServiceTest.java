package com.dwp.services.platform.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagedPreferenceServiceTest {

    @Mock
    private ManagedPreferenceRepository repository;
    @Mock
    private PlatformAuditService auditService;

    private ObjectMapper objectMapper;
    private ManagedPreferenceService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new ManagedPreferenceService(repository, objectMapper, auditService);
    }

    @Test
    void createsARequestOnlyForAnExceptionEligibleManagedPath() {
        var policy = policy(true);
        var input = new ManagedPreferenceDtos.CreateExceptionRequest(
                "appearance.accentColor", objectMapper.getNodeFactory().textNode("#0055CC"),
                "Required for an approved accessibility accommodation.",
                "The current contrast causes a material task completion barrier.", null);
        var created = request("PENDING", 0L);
        when(repository.policy(7L)).thenReturn(policy);
        when(repository.createRequest(7L, 11L, policy, policy.rules().get(0), input))
                .thenReturn(created);

        var result = service.requestException(7L, 11L, "corr", input);

        assertThat(result.requestState()).isEqualTo("PENDING");
        verify(auditService).success(
                eq(7L), eq(11L), eq("personal-preference.exception-requested"),
                eq("PREFERENCE_EXCEPTION_REQUEST"), eq(created.requestId().toString()),
                eq("corr"), org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void rejectsUnknownAndNonExceptionEligibleManagedPaths() {
        var unknown = new ManagedPreferenceDtos.CreateExceptionRequest(
                "navigation.unknown", objectMapper.getNodeFactory().textNode("top"),
                "This is a sufficiently detailed business justification.",
                "This is a sufficiently detailed business impact statement.", null);
        when(repository.policy(7L)).thenReturn(policy(true));

        assertThatThrownBy(() -> service.requestException(7L, 11L, null, unknown))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        var denied = new ManagedPreferenceDtos.CreateExceptionRequest(
                "appearance.accentColor", objectMapper.getNodeFactory().textNode("#0055CC"),
                "This is a sufficiently detailed business justification.",
                "This is a sufficiently detailed business impact statement.", null);
        when(repository.policy(7L)).thenReturn(policy(false));
        assertThatThrownBy(() -> service.requestException(7L, 11L, null, denied))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    @Test
    void administratorDecisionIsAuditedWithBeforeAndAfterState() {
        UUID requestId = UUID.fromString("51000000-0000-0000-0000-000000000010");
        var before = request("PENDING", 2L);
        var after = request("APPROVED", 3L);
        var decision = new ManagedPreferenceDtos.DecideExceptionRequest(
                "APPROVED", "Approved under accessibility policy ACC-42.", "CASE-42", 2L);
        when(repository.adminRequests(7L, "ALL")).thenReturn(List.of(before));
        when(repository.decideRequest(7L, 99L, requestId, decision)).thenReturn(after);

        var result = service.decideRequest(7L, 99L, "corr", requestId, decision);

        assertThat(result.requestState()).isEqualTo("APPROVED");
        verify(auditService).success(
                eq(7L), eq(99L), eq("personal-preference.exception-decided"),
                eq("PREFERENCE_EXCEPTION_REQUEST"), eq(requestId.toString()), eq("corr"),
                any(), any());
    }

    private ManagedPreferenceDtos.ManagedPreferencePolicy policy(boolean exceptionAllowed) {
        UUID policyId = UUID.fromString("51000000-0000-0000-0000-000000000001");
        var rule = new ManagedPreferenceDtos.ManagedPreferenceRule(
                UUID.fromString("51000000-0000-0000-0000-000000000002"),
                "appearance.accentColor", "settings.brandAccent.title",
                objectMapper.getNodeFactory().nullNode(), exceptionAllowed, 0L);
        return new ManagedPreferenceDtos.ManagedPreferencePolicy(
                policyId, "TENANT", "TENANT_EXPERIENCE_POLICY", "ROLE", "TENANT_ADMIN",
                "Tenant administrator", "/admin/experience/preference-exceptions",
                List.of(rule.preferencePath()), List.of(rule), 0L);
    }

    private ManagedPreferenceDtos.PreferenceExceptionRequest request(String state, long version) {
        return new ManagedPreferenceDtos.PreferenceExceptionRequest(
                UUID.fromString("51000000-0000-0000-0000-000000000010"), 11L,
                "appearance.accentColor", objectMapper.getNodeFactory().textNode("#0055CC"),
                "Required for an approved accessibility accommodation.",
                "The current contrast causes a material task completion barrier.",
                state, "TENANT_ADMIN", null,
                "APPROVED".equals(state) ? "Approved under accessibility policy ACC-42." : null,
                "APPROVED".equals(state) ? "CASE-42" : null,
                "APPROVED".equals(state) ? 99L : null,
                "APPROVED".equals(state) ? OffsetDateTime.now() : null,
                OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now(), version);
    }
}
