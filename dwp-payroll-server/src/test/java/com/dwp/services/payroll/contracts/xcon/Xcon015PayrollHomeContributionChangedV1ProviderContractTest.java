package com.dwp.services.payroll.contracts.xcon;

import com.dwp.contracts.hris.xcon.v1.Xcon015PayrollHomeContributionChangedV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Xcon015PayrollHomeContributionChangedV1ProviderContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void emitsOnlyTheClosedPayrollReferenceContract() throws Exception {
        Instant generated = Instant.parse("2026-10-16T03:00:00Z");
        var value = new Xcon015PayrollHomeContributionChangedV1(
                "SELF", generated.plusSeconds(120), generated, "PAY",
                "a".repeat(64), "href_0123456789abcdef", 4,
                generated.plusSeconds(600), "DEGRADED", "payroll-summary");

        ObjectNode json = mapper.valueToTree(value);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "ownerPopulationScope", "freshUntil", "generatedAt", "module",
                "payloadDigest", "payloadRef", "scopeRevision", "staleAfter",
                "state", "widgetKey");
        assertThat(mapper.treeToValue(json, Xcon015PayrollHomeContributionChangedV1.class))
                .isEqualTo(value);
        assertThat(json.toString()).doesNotContain(
                "gross", "net", "worker", "bank", "tax", "resultLines");
    }

    @Test
    void rejectsUnknownFieldsAndInvalidModuleWidgetOrDigest() throws Exception {
        Instant generated = Instant.parse("2026-10-16T03:00:00Z");
        var value = new Xcon015PayrollHomeContributionChangedV1(
                "LEGAL_ENTITY", generated.plusSeconds(120), generated, "PAY",
                "b".repeat(64), "href_0123456789abcdef", 8,
                generated.plusSeconds(600), "FRESH", "payroll-executive");
        ObjectNode unknown = mapper.valueToTree(value);
        unknown.put("amount", "999");
        assertThatThrownBy(() -> mapper.treeToValue(
                unknown, Xcon015PayrollHomeContributionChangedV1.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new Xcon015PayrollHomeContributionChangedV1(
                "SELF", generated.plusSeconds(120), generated, "HRM", "x",
                "href_invalid", 0, generated.plusSeconds(600), "FRESH",
                "people-summary"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
