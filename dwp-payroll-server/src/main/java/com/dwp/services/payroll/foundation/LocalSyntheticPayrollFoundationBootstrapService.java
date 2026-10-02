package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Seeds a disposable foundation through the PAY application service itself.
 * This avoids privileged SQL and preserves command receipts, idempotency,
 * simulation rules, dependency freshness, and author/publisher separation.
 */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.payroll-foundation.synthetic-bootstrap.enabled",
        havingValue = "true")
final class LocalSyntheticPayrollFoundationBootstrapService {
    private final PayrollFoundationService payroll;
    private final String expectedRunId;

    LocalSyntheticPayrollFoundationBootstrapService(
            PayrollFoundationService payroll,
            @Value("${dwp.hris.payroll-foundation.synthetic-bootstrap.run-id:}")
                    String expectedRunId) {
        this.payroll = payroll;
        this.expectedRunId = expectedRunId == null ? "" : expectedRunId;
        if (!this.expectedRunId.matches("w1-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}")) {
            throw new IllegalStateException(
                    "Local synthetic payroll bootstrap requires an exact W1 run binding.");
        }
    }

    LocalSyntheticPayrollFoundationBootstrapDtos.BootstrapResponse bootstrap(
            LocalSyntheticPayrollFoundationBootstrapDtos.BootstrapRequest request) {
        if (!expectedRunId.equals(request.runId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic payroll request is not bound to this runtime.");
        }
        PayrollFoundationAccess.Actor author = PayrollFoundationAccess.actor(
                request.tenantId(),
                request.authorActorId(),
                "SYNTHETIC_FIXTURE_AUTHOR",
                "APP.HRIS:VIEW,PAYROLL_FOUNDATION:VIEW,"
                        + "PAYROLL_FOUNDATION:EDIT,PAYROLL_FOUNDATION:SIMULATE",
                "PAYROLL_CONFIGURATION",
                request.legalEntityId().toString(),
                PayrollFoundationAccess.compatibilityPolicy());
        if (payroll.list(author).configurations().stream().anyMatch(configuration ->
                configuration.definition().legalEntity().id().equals(request.legalEntityId()))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Synthetic payroll bootstrap requires a fresh legal entity fixture.");
        }

        UUID createCommandId = deterministic("create", request);
        UUID simulateCommandId = deterministic("simulate", request);
        var created = payroll.create(
                author,
                createCommandId,
                expectedRunId + ":payroll-bootstrap:create",
                new PayrollFoundationModels.CreateConfigurationRequest(definition(request)));
        var simulated = payroll.simulate(
                author,
                created.configuration().configurationId(),
                simulateCommandId,
                expectedRunId + ":payroll-bootstrap:simulate",
                new PayrollFoundationModels.VersionCommand(created.configuration().version()));
        if (simulated.configuration().status() != PayrollFoundationModels.Lifecycle.SIMULATED
                || simulated.configuration().freshness().state()
                        != PayrollFoundationModels.FreshnessState.LIVE
                || simulated.configuration().authorId() != request.authorActorId()
                || simulated.configuration().simulation() == null
                || !simulated.configuration().simulation().successful()) {
            throw new IllegalStateException(
                    "Synthetic payroll foundation did not reach exact SIMULATED/LIVE state.");
        }
        String receipt = sha256(String.join("|",
                expectedRunId,
                Long.toString(request.tenantId()),
                Long.toString(request.authorActorId()),
                request.legalEntityId().toString(),
                simulated.configuration().configurationId().toString(),
                Long.toString(simulated.configuration().version()),
                createCommandId.toString(),
                simulateCommandId.toString()));
        return new LocalSyntheticPayrollFoundationBootstrapDtos.BootstrapResponse(
                expectedRunId,
                request.tenantId(),
                request.authorActorId(),
                request.legalEntityId(),
                simulated.configuration().configurationId(),
                simulated.configuration().version(),
                simulated.configuration().status().name(),
                simulated.configuration().freshness().state().name(),
                createCommandId,
                simulateCommandId,
                receipt);
    }

    private PayrollFoundationModels.FoundationDefinition definition(
            LocalSyntheticPayrollFoundationBootstrapDtos.BootstrapRequest request) {
        UUID payrollGroupId = deterministic("payroll-group", request);
        UUID calendarId = deterministic("pay-calendar", request);
        var currency = new PayrollFoundationModels.CurrencyCode("KRW");
        return new PayrollFoundationModels.FoundationDefinition(
                new PayrollFoundationModels.LegalEntity(
                        request.legalEntityId(),
                        "W1-KR-LEGAL",
                        "W1 synthetic Korean legal entity",
                        new PayrollFoundationModels.VersionedCountryPackReference(
                                "KR",
                                1,
                                sha256(expectedRunId + "|country-pack|KR|1"))),
                new PayrollFoundationModels.PayrollGroup(
                        payrollGroupId,
                        "W1-MONTHLY",
                        request.legalEntityId(),
                        Set.of(currency),
                        currency),
                new PayrollFoundationModels.PayCalendar(
                        calendarId,
                        payrollGroupId,
                        PayrollFoundationModels.Cadence.MONTHLY,
                        List.of(new PayrollFoundationModels.CalendarPeriod(
                                "2026-10",
                                LocalDate.of(2026, 10, 1),
                                LocalDate.of(2026, 10, 31),
                                LocalDate.of(2026, 10, 30)))),
                new PayrollFoundationModels.EffectivePeriod(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31)),
                Map.of(currency, new PayrollFoundationModels.RoundingPolicy(
                        0, RoundingMode.HALF_UP, BigDecimal.ONE)),
                List.of());
    }

    private UUID deterministic(
            String kind,
            LocalSyntheticPayrollFoundationBootstrapDtos.BootstrapRequest request) {
        return UUID.nameUUIDFromBytes(String.join("|",
                        expectedRunId,
                        kind,
                        Long.toString(request.tenantId()),
                        Long.toString(request.authorActorId()),
                        request.legalEntityId().toString())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
