package com.dwp.services.auth.provisioning;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalSyntheticIdentityBootstrapBoundaryTest {
    private static final String RUN_ID = "w1-20261002t010203z-0123abcd";
    private static final String TOKEN = "local-synthetic-token-0123456789abcdef";

    @Test
    void boundaryBeansAreAbsentByDefault() {
        new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                .withUserConfiguration(
                        LocalSyntheticIdentityBootstrapService.class,
                        LocalSyntheticIdentityBootstrapController.class,
                        LocalSyntheticIdentityBootstrapSecurityConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticIdentityBootstrapService.class);
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticIdentityBootstrapController.class);
                });
    }

    @Test
    void enabledBoundaryRejectsShortTokenDuringContextStartup() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
                .withBean(ObjectMapper.class,
                        () -> new ObjectMapper().findAndRegisterModules())
                .withUserConfiguration(LocalSyntheticIdentityBootstrapSecurityConfig.class)
                .withPropertyValues(
                        "dwp.synthetic-identity-bootstrap.enabled=true",
                        "DWP_ENVIRONMENT=local",
                        "dwp.synthetic-identity-bootstrap.token=short")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRequiresLocalEnvironmentAndLongUnpaddedToken() {
        assertThatThrownBy(() ->
                LocalSyntheticIdentityBootstrapSecurityConfig.requireLocalConfiguration(
                        "production", TOKEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                LocalSyntheticIdentityBootstrapSecurityConfig.requireLocalConfiguration(
                        "local", "too-short"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                LocalSyntheticIdentityBootstrapSecurityConfig.requireLocalConfiguration(
                        "local", TOKEN + " "))
                .isInstanceOf(IllegalStateException.class);
        LocalSyntheticIdentityBootstrapSecurityConfig.requireLocalConfiguration("local", TOKEN);
    }

    @Test
    void transportAcceptsOnlyLoopbackAndExactPurposeToken() throws Exception {
        var filter = new LocalSyntheticIdentityBootstrapSecurityConfig.LocalSyntheticTokenFilter(
                TOKEN, new ObjectMapper().findAndRegisterModules());

        MockHttpServletRequest accepted = request("127.0.0.1", TOKEN);
        MockFilterChain acceptedChain = new MockFilterChain();
        filter.doFilter(accepted, new MockHttpServletResponse(), acceptedChain);
        assertThat(acceptedChain.getRequest()).isSameAs(accepted);

        for (MockHttpServletRequest rejected : List.of(
                request("203.0.113.10", TOKEN),
                request("127.0.0.1", "x".repeat(40)),
                request("GET", "127.0.0.1", TOKEN),
                duplicateTokenRequest())) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(rejected, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void requestContractAllowsTheThreeTenantARolesAndRejectsExtraOrUnknownRoles() {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var validator = validation.getValidator();

            assertThat(validator.validate(activateRequest(List.of(
                    "HR_ADMIN", "PAYROLL_ADMIN", "PEOPLE_ADMIN"))))
                    .isEmpty();
            assertThat(validator.validate(activateRequest(List.of(
                    "HR_ADMIN", "PAYROLL_ADMIN", "PEOPLE_ADMIN", "HR_ADMIN"))))
                    .anySatisfy(violation -> assertThat(
                            violation.getConstraintDescriptor().getAnnotation())
                            .isInstanceOf(Size.class));
            assertThat(validator.validate(activateRequest(List.of(
                    "HR_ADMIN", "PAYROLL_ADMIN", "AUDITOR"))))
                    .anySatisfy(violation -> assertThat(
                            violation.getConstraintDescriptor().getAnnotation())
                            .isInstanceOf(Pattern.class));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void activatesExactlyOneInvitedAccountAndThenFailsTheOneShotFence() throws Exception {
        UUID publicId = UUID.fromString("5e1180fe-d98f-5bbc-bc19-60c21f69cd7e");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        ResultSet result = mock(ResultSet.class);
        when(result.getLong("tenant_id")).thenReturn(41L);
        when(result.getObject("public_id", UUID.class)).thenReturn(publicId);
        when(result.getString("tenant_status")).thenReturn("ACTIVE");
        when(result.getLong("user_id")).thenReturn(501L);
        when(result.getString("user_status")).thenReturn("INVITED");
        when(result.getLong("user_account_id")).thenReturn(601L);
        when(result.getString("account_status")).thenReturn("INVITED");
        when(result.getString("email_normalized"))
                .thenReturn("hris-w1-a-0123abcd@dwp.test");
        when(result.getObject("person_public_id", UUID.class)).thenReturn(null);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(result, 0)));
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("sys_role_assignment_policies")) {
                        assertThat(sql).contains(
                                "'HR_ADMIN', 'PAYROLL_ADMIN', 'PEOPLE_ADMIN'");
                        return List.of("HR_ADMIN", "PAYROLL_ADMIN", "PEOPLE_ADMIN");
                    }
                    if (sql.contains("sys_role_conflict_policies")) {
                        assertThat(sql).contains(
                                "('HR_ADMIN'), ('PAYROLL_ADMIN'), ('PEOPLE_ADMIN')");
                        return List.of();
                    }
                    return List.of("TENANT_ADMIN", "WORKSPACE_MEMBER");
                });
        when(encoder.encode("Aa1!synthetic-password")).thenReturn("encoded-password");
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenReturn(1, 1, 1, 1, 1, 0);
        var service = new LocalSyntheticIdentityBootstrapService(jdbc, encoder, RUN_ID);
        UUID personPublicId = UUID.fromString("1b730d64-fee8-531c-90f3-594bd10ffb17");
        var request = new LocalSyntheticIdentityBootstrapDtos.ActivateRequest(
                RUN_ID, publicId, 501L, personPublicId,
                "hris-w1-a-0123abcd@dwp.test", "Aa1!synthetic-password",
                List.of("HR_ADMIN", "PAYROLL_ADMIN", "PEOPLE_ADMIN"));

        var response = service.activate(request);

        assertThat(response.tenantId()).isEqualTo(41L);
        assertThat(response.administratorUserId()).isEqualTo(501L);
        assertThat(response.personPublicId()).isEqualTo(personPublicId);
        assertThat(response.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(response.roleCodes()).containsExactly(
                "HR_ADMIN", "PAYROLL_ADMIN", "PEOPLE_ADMIN");
        assertThat(response.receiptSha256()).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> service.activate(request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("one-shot fence");
    }

    @Test
    void tenantALaneRejectsTheFormerTwoRoleSetBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        var service = new LocalSyntheticIdentityBootstrapService(jdbc, encoder, RUN_ID);

        assertThatThrownBy(() -> service.activate(activateRequest(List.of(
                "HR_ADMIN", "PAYROLL_ADMIN"))))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("exact tenant lane policy");
        verifyNoInteractions(jdbc, encoder);
    }

    @Test
    void rejectsRequestsBoundToAnotherSyntheticRunBeforeDatabaseAccess() {
        var service = new LocalSyntheticIdentityBootstrapService(
                mock(JdbcTemplate.class), mock(PasswordEncoder.class), RUN_ID);
        var request = new LocalSyntheticIdentityBootstrapDtos.ActivateRequest(
                "w1-20261002t010203z-feedface",
                UUID.randomUUID(),
                501L,
                UUID.randomUUID(),
                "hris-w1-a-0123abcd@dwp.test",
                "Aa1!synthetic-password",
                List.of("HR_ADMIN", "PAYROLL_ADMIN", "PEOPLE_ADMIN"));

        assertThatThrownBy(() -> service.activate(request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not bound to this runtime");
    }

    private static LocalSyntheticIdentityBootstrapDtos.ActivateRequest activateRequest(
            List<String> roleCodes) {
        return new LocalSyntheticIdentityBootstrapDtos.ActivateRequest(
                RUN_ID,
                UUID.fromString("5e1180fe-d98f-5bbc-bc19-60c21f69cd7e"),
                501L,
                UUID.fromString("1b730d64-fee8-531c-90f3-594bd10ffb17"),
                "hris-w1-a-0123abcd@dwp.test",
                "Aa1!synthetic-password",
                roleCodes);
    }

    private static MockHttpServletRequest request(String remoteAddress, String token) {
        return request("POST", remoteAddress, token);
    }

    private static MockHttpServletRequest request(
            String method, String remoteAddress, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method, LocalSyntheticIdentityBootstrapSecurityConfig.LocalSyntheticTokenFilter.PATH);
        request.setRemoteAddr(remoteAddress);
        request.addHeader(LocalSyntheticIdentityBootstrapSecurityConfig.TOKEN_HEADER, token);
        return request;
    }

    private static MockHttpServletRequest duplicateTokenRequest() {
        MockHttpServletRequest request = request("127.0.0.1", TOKEN);
        request.addHeader(LocalSyntheticIdentityBootstrapSecurityConfig.TOKEN_HEADER, TOKEN);
        return request;
    }
}
