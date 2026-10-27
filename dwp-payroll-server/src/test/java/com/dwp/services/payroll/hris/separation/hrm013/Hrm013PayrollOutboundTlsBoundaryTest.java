package com.dwp.services.payroll.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import java.net.URI;
import org.junit.jupiter.api.Test;

class Hrm013PayrollOutboundTlsBoundaryTest {
    @Test
    void permitsOnlyExactHttpsEndpointsWithoutAuthorityOverrides() {
        assertThatCode(() -> Hrm013PayrollWorkloadCredentialProvider.requireEndpoint(
                URI.create("https://auth.internal"
                        + HrisServiceGrantAuthorityV1.PATH),
                HrisServiceGrantAuthorityV1.PATH)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Hrm013PayrollWorkloadCredentialProvider.requireEndpoint(
                URI.create("http://auth.internal" + HrisServiceGrantAuthorityV1.PATH),
                HrisServiceGrantAuthorityV1.PATH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hrm013PayrollWorkloadCredentialProvider.requireEndpoint(
                URI.create("https://auth.internal" + HrisServiceGrantAuthorityV1.PATH
                        + "?tenant=41"), HrisServiceGrantAuthorityV1.PATH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
