package com.dwp.services.payroll.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.OMIT;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.VIEW;
import static com.dwp.services.payroll.home.PayrollHomeContributionProjectorTest.decisions;
import static com.dwp.services.payroll.home.PayrollHomeContributionProjectorTest.self;
import static com.dwp.services.payroll.home.PayrollHomeContributionProjectorTest.selfSource;
import static com.dwp.services.payroll.home.PayrollHomePorts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayrollHomeContributionServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-10-16T03:01:00Z"), ZoneOffset.UTC);

    @Test
    void materializesOnlyTheCurrentlyRecomputedDigestBoundReference() {
        var fixture = fixture();
        var expected = fixture.projector.project(fixture.authority, selfSource(9));

        var actual = fixture.service.materialize(
                expected.payloadRef(),
                HomeMaterializationWorkloadProofV1.Endpoint.PAYROLL.path(expected.payloadRef()),
                List.of());

        assertThat(actual).isEqualTo(expected);
        assertThatThrownBy(() -> fixture.service.materialize(
                "href_0000000000000000",
                HomeMaterializationWorkloadProofV1.Endpoint.PAYROLL
                        .path("href_0000000000000000"),
                List.of()))
                .isInstanceOf(PayrollHomeContributionService.PayrollHomeNotFoundException.class);
    }

    @Test
    void staleMaterializationFailsClosedEvenWhenReferenceMatches() {
        var fixture = fixture(Clock.fixed(
                Instant.parse("2026-10-16T03:11:00Z"), ZoneOffset.UTC));
        var expected = fixture.projector.project(fixture.authority, selfSource(9));

        assertThatThrownBy(() -> fixture.service.materialize(
                expected.payloadRef(),
                HomeMaterializationWorkloadProofV1.Endpoint.PAYROLL.path(expected.payloadRef()),
                List.of()))
                .isInstanceOf(PayrollHomeContributionService.PayrollHomeNotFoundException.class);
    }

    @Test
    void ownerTransactionPublishesTheSameClosedMaterialization() {
        var fixture = fixture();

        var value = fixture.service.publishCurrent(fixture.authority);

        verify(fixture.events).record(fixture.authority.tenantId(), value);
    }

    private Fixture fixture() {
        return fixture(clock);
    }

    private Fixture fixture(Clock serviceClock) {
        var authority = self(decisions(OMIT, VIEW, VIEW, OMIT));
        PayrollHomeAdmission admission = mock(PayrollHomeAdmission.class);
        when(admission.admit(anyString(), anyList())).thenReturn(
                new PayrollHomeAdmission.AdmittedPayrollHomeRequest(null, authority));
        PayrollHomeProjectionSource source = mock(PayrollHomeProjectionSource.class);
        when(source.load(any())).thenReturn(selfSource(9));
        PayrollHomeEventPublisher events = mock(PayrollHomeEventPublisher.class);
        var codec = new PayrollHomePayloadCodec(
                "payroll-home-test-secret-32-bytes-minimum", mapper);
        var projector = new PayrollHomeContributionProjector(
                codec, Duration.ofMinutes(2), Duration.ofMinutes(10));
        var service = new PayrollHomeContributionService(
                admission, source, projector, codec, events, serviceClock);
        return new Fixture(authority, projector, events, service);
    }

    private record Fixture(
            ResolvedPayrollHomeAuthority authority,
            PayrollHomeContributionProjector projector,
            PayrollHomeEventPublisher events,
            PayrollHomeContributionService service) {
    }
}
