package com.dwp.services.payroll.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@SuppressWarnings("auxiliaryclass")
class PayrollHomeContributionControllerTest {

    private static final String REFERENCE = "href_0123456789abcdef";
    private static final String PATH =
            "/internal/hris-payroll/v1/home-contributions/" + REFERENCE;
    private final PayrollHomeContributionController controller =
            new PayrollHomeContributionController(null);

    @Test
    void rejectsTransferEncodingAndBodyBeforeWorkloadAdmission() {
        assertThat(PayrollHomeContributionController.class.isAnnotationPresent(
                io.swagger.v3.oas.annotations.Hidden.class)).isTrue();

        MockHttpServletRequest chunked = new MockHttpServletRequest("GET", PATH);
        chunked.addHeader("Transfer-Encoding", "chunked");
        assertRejected(chunked);

        MockHttpServletRequest body = new MockHttpServletRequest("GET", PATH);
        body.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertRejected(body);
    }

    private void assertRejected(MockHttpServletRequest request) {
        assertThatThrownBy(() -> controller.materialize(REFERENCE, request))
                .isInstanceOf(
                        PayrollHomeContributionService.PayrollHomeNotFoundException.class);
        var response = new PayrollHomeContributionFailureHandler().opaqueNotFound(
                new PayrollHomeContributionService.PayrollHomeNotFoundException());
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo("PAYROLL_HOME_CONTRIBUTION_NOT_FOUND");
    }
}
