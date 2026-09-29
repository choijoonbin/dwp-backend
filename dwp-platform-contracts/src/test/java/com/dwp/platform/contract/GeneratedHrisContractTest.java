package com.dwp.platform.contract;

import com.dwp.contracts.hris.xcon.v1.Xcon002CompensationBasisSnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon003WorkerChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon005AssignmentChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon006OrganizationChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1.ClosedTimeLine;
import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1.ApprovedCompensationPlanLine;
import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.platform.contracts.hris.generated.ConnectorMappingVersionV1;
import com.dwp.platform.contracts.hris.generated.ConnectorReconciliationReceiptV1;
import com.dwp.platform.contracts.hris.generated.HrisConfigurationInvalidatedV1;
import com.dwp.platform.contracts.hris.generated.HrisConfigurationVersionV1;
import com.dwp.platform.contracts.hris.generated.HrisAuditOutboxEventV1;
import com.dwp.platform.contracts.hris.generated.HrisNotificationIntentV1;
import com.dwp.platform.contracts.hris.generated.HrisNotificationReceiptV1;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class GeneratedHrisContractTest {

    private static final String DIGEST = "a".repeat(64);
    private static final UUID ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ID_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void snapshotsMutableCollectionsAtTheGeneratedBoundary() {
        List<String> changedReferences = new ArrayList<>(List.of("MANAGER"));
        Xcon005AssignmentChangedV1 event = new Xcon005AssignmentChangedV1(
                UUID.randomUUID(),
                changedReferences,
                LocalDate.parse("2026-09-11"),
                UUID.randomUUID(),
                "CHANGE_MANAGER",
                1L);

        changedReferences.add("LOCATION");

        assertThat(event.changedReferences()).containsExactly("MANAGER");
        assertThatThrownBy(() -> event.changedReferences().add("LOCATION"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingRequiredValuesAndInvalidCanonicalConstraints() {
        assertThatThrownBy(() -> new Xcon003WorkerChangedV1(
                null,
                LocalDate.parse("2026-09-11"),
                1L,
                UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("changeType");

        assertThatThrownBy(() -> new Xcon006OrganizationChangedV1(
                List.of(UUID.randomUUID()),
                LocalDate.parse("2026-09-11"),
                UUID.randomUUID(),
                "not-a-digest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshotDigest");

        assertThatThrownBy(() -> new HrisConfigurationInvalidatedV1(
                UUID.randomUUID(),
                "CONFIGURATION",
                "GLOBAL",
                null,
                0L,
                null,
                "CORRECTION",
                Instant.parse("2026-09-11T00:00:00Z"),
                1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalidatedVersion");
    }

    @Test
    void rejectsCanonicalInvalidArrayItems() {
        assertThatThrownBy(() -> new Xcon005AssignmentChangedV1(
                UUID.randomUUID(),
                List.of("not schema code"),
                LocalDate.parse("2026-09-11"),
                UUID.randomUUID(),
                "HIRE",
                1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changedReferencesItem");

        assertThatThrownBy(() -> new AutomationHandlerManifestV1(
                "handler.key",
                "owner.service",
                "Handler.v1",
                "schema:input",
                "schema:receipt",
                DIGEST,
                List.of("ROOT"),
                "NONE",
                1,
                "DRAFT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedScopeTypesItem");
    }

    @Test
    void serializesCanonicalDecimalStringsAndRoundTripsAllGeneratedValueTypes() throws Exception {
        ObjectMapper mapper = canonicalMapper();

        Xcon002CompensationBasisSnapshotV1 inputAmount = compensationBasis("123.4500");
        String inputJson = mapper.writeValueAsString(inputAmount);
        assertThat(inputJson).contains("\"amount\":\"123.45\"");
        assertThat(mapper.readValue(inputJson, Xcon002CompensationBasisSnapshotV1.class))
                .isEqualTo(inputAmount);
        assertThat(mapper.writeValueAsBytes(compensationBasis("123.45")))
                .isEqualTo(mapper.writeValueAsBytes(inputAmount));

        ClosedTimeLine quantity = closedTimeLine("8.500000");
        String quantityJson = mapper.writeValueAsString(quantity);
        assertThat(quantityJson).contains("\"quantity\":\"8.5\"");
        assertThat(mapper.readValue(quantityJson, ClosedTimeLine.class)).isEqualTo(quantity);
        assertThat(mapper.writeValueAsBytes(closedTimeLine("8.5")))
                .isEqualTo(mapper.writeValueAsBytes(quantity));

        ApprovedCompensationPlanLine postedMoney = planLine(ID_1, 1, "100.0000");
        String postedJson = mapper.writeValueAsString(postedMoney);
        assertThat(postedJson).contains("\"approvedAmount\":\"100\"");
        assertThat(mapper.readValue(postedJson, ApprovedCompensationPlanLine.class))
                .isEqualTo(postedMoney);
        assertThat(mapper.writeValueAsBytes(planLine(ID_1, 1, "100")))
                .isEqualTo(mapper.writeValueAsBytes(postedMoney));
    }

    @Test
    void rejectsNonCanonicalDecimalWireTokensAndCanonicalizesSignedZero() throws Exception {
        ObjectMapper mapper = canonicalMapper();
        String inputJson = mapper.writeValueAsString(compensationBasis("1.25"));
        for (String invalid : List.of(
                "1.25",
                "\"1e2\"",
                "\"+1\"",
                "\" 1\"",
                "\"01\"",
                "\"1.0000000\"",
                "\"10000000000000\"")) {
            assertDecimalRejected(
                    mapper,
                    inputJson.replace("\"amount\":\"1.25\"", "\"amount\":" + invalid),
                    Xcon002CompensationBasisSnapshotV1.class,
                    "InputAmount");
        }

        String quantityJson = mapper.writeValueAsString(closedTimeLine("8.5"));
        for (String invalid : List.of("8.5", "\"8e0\"", "\"8.0000000\"",
                "\"10000000000000\"")) {
            assertDecimalRejected(
                    mapper,
                    quantityJson.replace("\"quantity\":\"8.5\"", "\"quantity\":" + invalid),
                    ClosedTimeLine.class,
                    "QuantityDecimal");
        }

        String postedJson = mapper.writeValueAsString(planLine(ID_1, 1, "100"));
        for (String invalid : List.of("100", "\"1e2\"", "\"100.00000\"",
                "\"1000000000000000\"")) {
            assertDecimalRejected(
                    mapper,
                    postedJson.replace(
                            "\"approvedAmount\":\"100\"",
                            "\"approvedAmount\":" + invalid),
                    ApprovedCompensationPlanLine.class,
                    "PostedMoney");
        }

        Xcon002CompensationBasisSnapshotV1 zeroInput = mapper.readValue(
                inputJson.replace("\"amount\":\"1.25\"", "\"amount\":\"-0.000000\""),
                Xcon002CompensationBasisSnapshotV1.class);
        ClosedTimeLine zeroQuantity = mapper.readValue(
                quantityJson.replace("\"quantity\":\"8.5\"", "\"quantity\":\"-0.000000\""),
                ClosedTimeLine.class);
        ApprovedCompensationPlanLine zeroPosted = mapper.readValue(
                postedJson.replace(
                        "\"approvedAmount\":\"100\"",
                        "\"approvedAmount\":\"-0.0000\""),
                ApprovedCompensationPlanLine.class);

        assertThat(zeroInput.amount()).isEqualByComparingTo(BigDecimal.ZERO).hasScaleOf(0);
        assertThat(zeroQuantity.quantity()).isEqualByComparingTo(BigDecimal.ZERO).hasScaleOf(0);
        assertThat(zeroPosted.approvedAmount()).isEqualByComparingTo(BigDecimal.ZERO).hasScaleOf(0);
        assertThat(mapper.writeValueAsString(zeroInput)).contains("\"amount\":\"0\"");
        assertThat(mapper.writeValueAsString(zeroQuantity)).contains("\"quantity\":\"0\"");
        assertThat(mapper.writeValueAsString(zeroPosted))
                .contains("\"approvedAmount\":\"0\"");
    }

    @Test
    void rejectsMissingNullCoercedDuplicateAdditionalAndInvalidNestedWireValues()
            throws Exception {
        ObjectMapper mapper = canonicalMapper();
        String invocation = mapper.writeValueAsString(automationInvocation());
        assertThat(invocation)
                .contains("\"scopeId\":null")
                .contains("\"expectedItemCount\":7");

        List<String> malformedInvocations = List.of(
                invocation.replace("\"scopeId\":null,", ""),
                invocation.replace(",\"expectedItemCount\":7", ""),
                invocation.replace("\"expectedItemCount\":7", "\"expectedItemCount\":null"),
                invocation.replace("\"expectedItemCount\":7", "\"expectedItemCount\":\"7\""),
                invocation.replace("\"expectedItemCount\":7", "\"expectedItemCount\":1.75"),
                invocation.replace("\"handlerKey\":\"hris.payroll\"", "\"handlerKey\":7"),
                invocation.replace(
                        "\"requestedAt\":\"2026-09-11T00:00:00Z\"",
                        "\"requestedAt\":0"),
                invocation.replace(
                        "\"invocationId\":\"abcdefab-cdef-abcd-efab-cdefabcdefab\"",
                        "\"invocationId\":7"),
                invocation.replace(
                        "\"expectedItemCount\":7",
                        "\"expectedItemCount\":7,\"expectedItemCount\":7"),
                invocation.substring(0, invocation.length() - 1) + ",\"unknown\":true}");
        for (String malformed : malformedInvocations) {
            assertThatThrownBy(() -> mapper.readValue(malformed, AutomationInvocationV1.class))
                    .isInstanceOf(Exception.class);
        }

        String receipt = mapper.writeValueAsString(automationReceipt());
        for (String malformed : List.of(
                receipt.replace(",\"retryable\":true", ""),
                receipt.replace("\"retryable\":true", "\"retryable\":null"),
                receipt.replace("\"retryable\":true", "\"retryable\":\"true\""))) {
            assertThatThrownBy(() -> mapper.readValue(
                    malformed, AutomationRunItemReceiptV1.class))
                    .isInstanceOf(Exception.class);
        }

        String nested = mapper.writeValueAsString(closedTimeLine("8.5"));
        List<String> malformedNestedValues = List.of(
                nested.replace(",\"currency\":null", ""),
                nested.replace("\"sourceEntryCount\":1", "\"sourceEntryCount\":1.0"),
                nested.replace(
                        "\"sourceEntryCount\":1",
                        "\"sourceEntryCount\":1,\"sourceEntryCount\":1"),
                nested.substring(0, nested.length() - 1) + ",\"extra\":false}");
        for (int index = 0; index < malformedNestedValues.size(); index++) {
            String malformed = malformedNestedValues.get(index);
            assertThat(malformed)
                    .as("nested mutation %s must alter its fixture: %s", index, nested)
                    .isNotEqualTo(nested);
            Throwable rejection = catchThrowable(
                    () -> mapper.readValue(malformed, ClosedTimeLine.class));
            assertThat(rejection)
                    .as("nested strict-wire mutation %s: %s", index, malformed)
                    .isInstanceOf(Exception.class);
        }

        Xcon005AssignmentChangedV1 changed = new Xcon005AssignmentChangedV1(
                ID_1,
                List.of("MANAGER"),
                LocalDate.parse("2026-09-11"),
                ID_2,
                "HIRE",
                1L);
        String changedJson = mapper.writeValueAsString(changed);
        assertThatThrownBy(() -> mapper.readValue(
                changedJson.replace(
                        "\"changedReferences\":[\"MANAGER\"]",
                        "\"changedReferences\":[\"MANAGER\",7]"),
                Xcon005AssignmentChangedV1.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void serializesRequiredNullTemporalAndUuidFieldsCanonicallyWithHostileMapperSettings()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        AutomationInvocationV1 value = automationInvocation();

        String json = mapper.writeValueAsString(value);
        var tree = mapper.readTree(json);
        assertThat(tree.has("scopeId")).isTrue();
        assertThat(tree.get("scopeId").isNull()).isTrue();
        assertThat(tree.get("invocationId").isTextual()).isTrue();
        assertThat(tree.get("invocationId").textValue())
                .isEqualTo("abcdefab-cdef-abcd-efab-cdefabcdefab");
        assertThat(tree.get("requestedAt").isTextual()).isTrue();
        assertThat(tree.get("requestedAt").textValue())
                .isEqualTo("2026-09-11T00:00:00Z");
        assertThat(mapper.readValue(json, AutomationInvocationV1.class)).isEqualTo(value);
        String uppercaseUuid = json.replace(
                "abcdefab-cdef-abcd-efab-cdefabcdefab",
                "ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB");
        assertThat(mapper.writeValueAsString(
                mapper.readValue(uppercaseUuid, AutomationInvocationV1.class)))
                .contains("\"invocationId\":\"abcdefab-cdef-abcd-efab-cdefabcdefab\"")
                .doesNotContain("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB");

        Xcon002CompensationBasisSnapshotV1 compensation = compensationBasis("1.25");
        String compensationJson = mapper.writeValueAsString(compensation);
        var compensationTree = mapper.readTree(compensationJson);
        assertThat(compensationTree.has("validTo")).isTrue();
        assertThat(compensationTree.get("validTo").isNull()).isTrue();
        assertThat(compensationTree.get("asOf").isTextual()).isTrue();
        assertThat(compensationTree.get("validFrom").isTextual()).isTrue();
        assertThat(compensationTree.get("amount").isTextual()).isTrue();
        assertThat(mapper.readValue(
                compensationJson, Xcon002CompensationBasisSnapshotV1.class))
                .isEqualTo(compensation);

        Xcon006OrganizationChangedV1 organization = new Xcon006OrganizationChangedV1(
                List.of(UUID.fromString("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB")),
                LocalDate.parse("2026-09-11"),
                ID_1,
                DIGEST);
        String organizationJson = mapper.writeValueAsString(organization);
        assertThat(mapper.readTree(organizationJson)
                .get("changedOrganizationPublicIds").get(0).textValue())
                .isEqualTo("abcdefab-cdef-abcd-efab-cdefabcdefab");
        assertThat(mapper.readValue(organizationJson, Xcon006OrganizationChangedV1.class))
                .isEqualTo(organization);
    }

    @Test
    void mapsCanonicalFormatsAndNumericWidthsToStableJavaTypes() throws Exception {
        assertThat(componentType(Xcon002CompensationBasisSnapshotV1.class, "amount"))
                .isEqualTo(BigDecimal.class);
        assertThat(componentType(Xcon002CompensationBasisSnapshotV1.class, "asOf"))
                .isEqualTo(Instant.class);
        assertThat(componentType(Xcon002CompensationBasisSnapshotV1.class, "validFrom"))
                .isEqualTo(LocalDate.class);
        assertThat(componentType(Xcon002CompensationBasisSnapshotV1.class, "workerPublicId"))
                .isEqualTo(UUID.class);
        assertThat(componentType(AutomationInvocationV1.class, "automationVersion"))
                .isEqualTo(long.class);
        assertThat(componentType(AutomationInvocationV1.class, "expectedItemCount"))
                .isEqualTo(int.class);
        assertThat(componentType(AutomationRunItemReceiptV1.class, "retryable"))
                .isEqualTo(boolean.class);
    }

    @Test
    void enforcesClosedTimeUnitCurrencyBinding() {
        UUID workerId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        String digest = "a".repeat(64);

        ClosedTimeLine amount = new ClosedTimeLine(
                workerId, assignmentId, "BONUS", new BigDecimal("10.25"),
                "USD", 1, digest, "AMOUNT");
        ClosedTimeLine hours = new ClosedTimeLine(
                workerId, assignmentId, "REGULAR", new BigDecimal("8.0"),
                null, 1, digest, "HOUR");

        assertThat(amount.currency()).isEqualTo("USD");
        assertThat(hours.currency()).isNull();
        assertThatThrownBy(() -> new ClosedTimeLine(
                workerId, assignmentId, "BONUS", new BigDecimal("10.25"),
                null, 1, digest, "AMOUNT"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> new ClosedTimeLine(
                workerId, assignmentId, "BONUS", new BigDecimal("10.25"),
                "ZZZ", 1, digest, "AMOUNT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recognized ISO 4217 code");
        assertThatThrownBy(() -> new ClosedTimeLine(
                workerId, assignmentId, "REGULAR", new BigDecimal("8.0"),
                "USD", 1, digest, "HOUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be null");
    }

    @Test
    void enforcesClosedTimeHeaderLineCount() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new Xcon008ClosedTimeResultV1(
                id, 1L, id, 1L, 1L, 1, List.of(), id, DIGEST, id,
                DIGEST, DIGEST, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineCount must equal lines.size");

        assertThat(new Xcon008ClosedTimeResultV1(
                id, 1L, id, 1L, 1L, 0, List.of(), id, DIGEST, id,
                DIGEST, DIGEST, null, 0).lines()).isEmpty();
    }

    @Test
    void enforcesCompensationPlanLineCountSequenceAndIdentity() {
        UUID firstLineId = UUID.randomUUID();
        UUID secondLineId = UUID.randomUUID();
        ApprovedCompensationPlanLine first = planLine(firstLineId, 1);
        ApprovedCompensationPlanLine second = planLine(secondLineId, 2);

        assertThat(planSnapshot(2, List.of(first, second)).lines())
                .containsExactly(first, second);
        assertThatThrownBy(() -> planSnapshot(2, List.of(first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineCount must equal lines.size");
        assertThatThrownBy(() -> planSnapshot(
                2, List.of(first, planLine(secondLineId, 3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineSequence must be contiguous");
        assertThatThrownBy(() -> planSnapshot(
                2, List.of(first, planLine(firstLineId, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineId must be unique");
    }

    @Test
    void enforcesConfigurationScopeEffectiveRangeAndPublishedApproval() {
        UUID configurationId = UUID.randomUUID();
        LocalDate start = LocalDate.parse("2026-09-11");

        assertThat(configuration(
                configurationId, "GLOBAL", null, "DRAFT", start.plusDays(1), null).scopeId())
                .isNull();
        assertThat(configuration(
                configurationId, "TENANT", UUID.randomUUID(), "PUBLISHED",
                start.plusDays(1), UUID.randomUUID()).state()).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> configuration(
                configurationId, "GLOBAL", UUID.randomUUID(), "DRAFT",
                start.plusDays(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null for GLOBAL");
        assertThatThrownBy(() -> configuration(
                configurationId, "TENANT", null, "DRAFT", start.plusDays(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-GLOBAL");
        assertThatThrownBy(() -> configuration(
                configurationId, "GLOBAL", null, "DRAFT", start, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveTo must be after");
        assertThatThrownBy(() -> configuration(
                configurationId, "GLOBAL", null, "PUBLISHED", start.plusDays(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PUBLISHED");
    }

    @Test
    void enforcesConnectorFourEyesActiveEvidenceAndEffectiveRange() {
        LocalDate start = LocalDate.parse("2026-09-11");
        UUID receiptId = UUID.randomUUID();

        assertThat(mapping("DRAFT", null, null, null, start.plusDays(1)).state())
                .isEqualTo("DRAFT");
        assertThat(mapping("ACTIVE", "actor:checker", receiptId, DIGEST, start.plusDays(1)).state())
                .isEqualTo("ACTIVE");
        assertThatThrownBy(() -> mapping(
                "APPROVED", "actor:maker", receiptId, DIGEST, start.plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
        assertThatThrownBy(() -> mapping(
                "ACTIVE", null, receiptId, DIGEST, start.plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvedByActorRef");
        assertThatThrownBy(() -> mapping(
                "ACTIVE", "actor:checker", null, DIGEST, start.plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvalReceiptId");
        assertThatThrownBy(() -> mapping(
                "ACTIVE", "actor:checker", receiptId, null, start.plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dryRunDigest");
        assertThatThrownBy(() -> mapping(
                "DRAFT", null, null, null, start))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveTo must be after");
    }

    @Test
    void enforcesReconciliationCountBalanceAndPartialErrorReference() {
        assertThat(receipt("SUCCEEDED", 3, 2, 1, 0, null).sourceCount()).isEqualTo(3);
        assertThat(receipt("PARTIAL", 3, 2, 1, 0, "report:1").errorReportRef())
                .isEqualTo("report:1");
        assertThatThrownBy(() -> receipt("SUCCEEDED", 4, 2, 1, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceCount must equal");
        assertThatThrownBy(() -> receipt("PARTIAL", 3, 2, 1, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PARTIAL");
    }

    @Test
    void enforcesNotificationRecipientStateRetryAndAuditFailureInvariants() {
        assertThat(notificationIntent(List.of(11L, 12L), List.of(12L))
                .recipientUserIds()).containsExactly(11L, 12L);
        assertThatThrownBy(() -> notificationIntent(List.of(11L), List.of(11L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minus excludedUserIds");

        assertThat(notificationReceipt(
                "DELIVERED", "EMAIL", ID_3, DIGEST, DIGEST, null, false, null).state())
                .isEqualTo("DELIVERED");
        assertThatThrownBy(() -> notificationReceipt(
                "FAILED", "EMAIL", null, null, null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorCode is required");
        assertThatThrownBy(() -> notificationReceipt(
                "ADMITTED", "EMAIL", null, null, null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pre-materialization");
        assertThatThrownBy(() -> notificationReceipt(
                "FAILED", "EMAIL", null, null, null, "PROVIDER_TIMEOUT", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextAttemptAt");
        assertThatThrownBy(() -> notificationReceipt(
                "DEAD_LETTERED", "EMAIL", null, null, null,
                "PROVIDER_TIMEOUT", true, Instant.parse("2026-09-11T01:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only FAILED");

        assertThat(notificationReceipt(
                "RECEIVED", "NONE", null, null, null, null, false, null, 0).state())
                .isEqualTo("RECEIVED");
        assertThat(notificationReceipt(
                "MATERIALIZED", "EMAIL", ID_3, DIGEST, null, null, false, null, 0).state())
                .isEqualTo("MATERIALIZED");
        assertThat(notificationReceipt(
                "DELIVERED", "IN_APP", ID_3, DIGEST, null, null, false, null, 0).state())
                .isEqualTo("DELIVERED");
        assertThatThrownBy(() -> notificationReceipt(
                "RECEIVED", "NONE", ID_3, null, null, null, false, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pre-materialization");
        assertThatThrownBy(() -> notificationReceipt(
                "MATERIALIZED", "EMAIL", ID_3, DIGEST, DIGEST, null, false, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unattempted");
        assertThatThrownBy(() -> notificationReceipt(
                "DELIVERED", "EMAIL", ID_3, DIGEST, null, null, false, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider receipt");
        assertThatThrownBy(() -> notificationReceipt(
                "DELIVERED", "IN_APP", ID_3, DIGEST, DIGEST, null, false, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IN_APP");
        assertThatThrownBy(() -> notificationReceipt(
                "FAILED", "EMAIL", ID_3, DIGEST, null,
                "PROVIDER_TIMEOUT", false, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempted delivery");
        assertThatThrownBy(() -> notificationReceipt(
                "CANCELED", "EMAIL", ID_3, DIGEST, DIGEST, null, false, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CANCELED");

        assertThat(auditEvent("SUCCESS", null).evidenceDigest()).isEqualTo(DIGEST);
        assertThatThrownBy(() -> auditEvent("FAILED", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorCode is required");
        assertThatThrownBy(() -> auditEvent("SUCCESS", "UNEXPECTED_ERROR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden for SUCCESS");
    }

    @Test
    void notificationReceiptStateMatrixIsClosedAndExact() {
        for (String state : List.of("RECEIVED", "ADMITTED", "SUPPRESSED")) {
            assertThat(notificationReceipt(
                    state, "NONE", null, null, null, null, false, null, 0).state())
                    .isEqualTo(state);
        }
        for (String state : List.of("MATERIALIZED", "QUEUED")) {
            assertThat(notificationReceipt(
                    state, "EMAIL", ID_3, DIGEST, null, null, false, null, 0).state())
                    .isEqualTo(state);
        }
        assertThat(notificationReceipt(
                "DELIVERED", "EMAIL", ID_3, DIGEST, DIGEST, null, false, null, 1).state())
                .isEqualTo("DELIVERED");
        assertThat(notificationReceipt(
                "FAILED", "EMAIL", ID_3, DIGEST, null,
                "PROVIDER_TIMEOUT", true, Instant.parse("2026-09-11T01:00:00Z"), 1).state())
                .isEqualTo("FAILED");
        assertThat(notificationReceipt(
                "DEAD_LETTERED", "EMAIL", ID_3, DIGEST, DIGEST,
                "PROVIDER_REJECTED", false, null, 3).state()).isEqualTo("DEAD_LETTERED");
        assertThat(notificationReceipt(
                "CANCELED", "EMAIL", ID_3, DIGEST, null, null, false, null, 0).state())
                .isEqualTo("CANCELED");

        assertThatThrownBy(() -> notificationReceipt(
                "QUEUED", "EMAIL", ID_3, DIGEST, null, null, false, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unattempted");
        assertThatThrownBy(() -> notificationReceipt(
                "FAILED", "NONE", ID_3, DIGEST, null,
                "PROVIDER_TIMEOUT", false, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("post-materialization");
        assertThatThrownBy(() -> notificationReceipt(
                "CANCELED", "EMAIL", ID_3, DIGEST, DIGEST, null, false, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CANCELED");
    }

    @Test
    void notificationAndAuditContractsRemainStrictAndPrivacyMinimizedOnWire() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        HrisNotificationIntentV1 intent = notificationIntent(List.of(11L), List.of());
        String intentJson = mapper.writeValueAsString(intent);
        var intentTree = mapper.readTree(intentJson);
        assertThat(intentTree.has("threadRef")).isTrue();
        assertThat(intentTree.get("threadRef").isNull()).isTrue();
        assertThat(intentTree.get("requestedAt").isTextual()).isTrue();
        assertThat(intentTree.has("body")).isFalse();
        assertThat(intentTree.has("templateVariables")).isFalse();
        assertThat(intentTree.has("providerData")).isFalse();
        assertThat(mapper.readValue(intentJson, HrisNotificationIntentV1.class))
                .isEqualTo(intent);
        assertThatThrownBy(() -> mapper.readValue(
                intentJson.substring(0, intentJson.length() - 1) + ",\"body\":\"secret\"}",
                HrisNotificationIntentV1.class))
                .isInstanceOf(Exception.class);

        HrisAuditOutboxEventV1 event = auditEvent("SUCCESS", null);
        String eventJson = mapper.writeValueAsString(event);
        var eventTree = mapper.readTree(eventJson);
        assertThat(eventTree.has("beforeDigest")).isTrue();
        assertThat(eventTree.has("afterDigest")).isTrue();
        assertThat(eventTree.has("before")).isFalse();
        assertThat(eventTree.has("after")).isFalse();
        assertThat(eventTree.has("metadata")).isFalse();
        assertThat(mapper.readValue(eventJson, HrisAuditOutboxEventV1.class))
                .isEqualTo(event);
    }

    @Test
    void packagesCanonicalSchemasAndRuntimePolicyClassifications() throws Exception {
        String resource = "/META-INF/dwp/hris/canonical/"
                + "hris-contract-codegen-manifest.v1.json";
        try (var stream = GeneratedHrisContractTest.class.getResourceAsStream(resource)) {
            assertThat(stream).isNotNull();
            String manifest = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(manifest)
                    .contains("\"DOMAIN_RUNTIME\"")
                    .contains("\"GENERATED_CONSTRUCTOR\"")
                    .contains("\"extensionBindings\"");
        }
    }

    private static ApprovedCompensationPlanLine planLine(UUID lineId, int sequence) {
        return planLine(lineId, sequence, "100.00");
    }

    private static ApprovedCompensationPlanLine planLine(
            UUID lineId,
            int sequence,
            String amount) {
        return new ApprovedCompensationPlanLine(
                lineId,
                sequence,
                ID_2,
                ID_3,
                "BASE",
                "USD",
                new BigDecimal(amount),
                LocalDate.parse("2026-09-11"),
                null,
                DIGEST);
    }

    private static Xcon002CompensationBasisSnapshotV1 compensationBasis(String amount) {
        return new Xcon002CompensationBasisSnapshotV1(
                new BigDecimal(amount),
                Instant.parse("2026-09-11T00:00:00Z"),
                ID_1,
                "SALARY",
                ID_2,
                "USD",
                "MONTHLY",
                ID_3,
                ID_1,
                DIGEST,
                1L,
                1L,
                LocalDate.parse("2026-09-11"),
                null,
                ID_2);
    }

    private static ClosedTimeLine closedTimeLine(String quantity) {
        return new ClosedTimeLine(
                ID_1,
                ID_2,
                "REGULAR",
                new BigDecimal(quantity),
                null,
                1,
                DIGEST,
                "HOUR");
    }

    private static ObjectMapper canonicalMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static AutomationInvocationV1 automationInvocation() {
        return new AutomationInvocationV1(
                UUID.fromString("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB"),
                ID_1,
                1L,
                "hris.payroll",
                "AutomationInput.v1",
                "GLOBAL",
                null,
                Instant.parse("2026-09-11T00:00:00Z"),
                "actor:requester",
                "idem-key-1",
                "input:1",
                DIGEST,
                7,
                ID_2);
    }

    private static AutomationRunItemReceiptV1 automationReceipt() {
        return new AutomationRunItemReceiptV1(
                ID_1,
                1L,
                ID_2,
                1L,
                "subject:1",
                "RUNNING",
                1,
                Instant.parse("2026-09-11T00:00:00Z"),
                null,
                null,
                null,
                null,
                true,
                null,
                ID_3);
    }

    private static <T> void assertDecimalRejected(
            ObjectMapper mapper,
            String json,
            Class<T> type,
            String valueType) {
        assertThatThrownBy(() -> mapper.readValue(json, type))
                .hasMessageContaining(valueType);
    }

    private static Xcon020ApprovedCompensationPlanSnapshotV1 planSnapshot(
            int lineCount, List<ApprovedCompensationPlanLine> lines) {
        return new Xcon020ApprovedCompensationPlanSnapshotV1(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.parse("2026-09-11"),
                null,
                lineCount,
                lines,
                DIGEST,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                1L);
    }

    private static HrisConfigurationVersionV1 configuration(
            UUID configurationId,
            String scopeType,
            UUID scopeId,
            String state,
            LocalDate effectiveTo,
            UUID approvalReceiptId) {
        return new HrisConfigurationVersionV1(
                configurationId,
                "PAY_POLICY",
                scopeType,
                scopeId,
                1L,
                state,
                LocalDate.parse("2026-09-11"),
                effectiveTo,
                "PayPolicy.v1",
                DIGEST,
                approvalReceiptId);
    }

    private static ConnectorMappingVersionV1 mapping(
            String state,
            String approvedBy,
            UUID approvalReceiptId,
            String dryRunDigest,
            LocalDate effectiveTo) {
        return new ConnectorMappingVersionV1(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                state,
                "Source.v1",
                "Target.v1",
                DIGEST,
                dryRunDigest,
                "actor:maker",
                approvedBy,
                approvalReceiptId,
                LocalDate.parse("2026-09-11"),
                effectiveTo,
                "EXACT");
    }

    private static ConnectorReconciliationReceiptV1 receipt(
            String state,
            int sourceCount,
            int acceptedCount,
            int rejectedCount,
            int quarantinedCount,
            String errorReportRef) {
        return new ConnectorReconciliationReceiptV1(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                state,
                sourceCount,
                acceptedCount,
                rejectedCount,
                quarantinedCount,
                DIGEST,
                DIGEST,
                errorReportRef,
                DIGEST,
                Instant.parse("2026-09-11T00:00:00Z"),
                UUID.randomUUID());
    }

    private static HrisNotificationIntentV1 notificationIntent(
            List<Long> recipients,
            List<Long> exclusions) {
        return new HrisNotificationIntentV1(
                ID_1,
                ID_2,
                1L,
                "hris.payroll",
                "PAYROLL_READY",
                "PAYROLL_NOTICE",
                recipients,
                exclusions,
                null,
                "ko-KR",
                "PAYROLL_CLOSED",
                "actor:1",
                "worker:11",
                null,
                "payroll.closed",
                1L,
                DIGEST,
                "CONFIDENTIAL",
                true,
                Instant.parse("2026-09-12T00:00:00Z"),
                Instant.parse("2026-09-11T00:00:00Z"),
                "intent-key-1",
                ID_3);
    }

    private static HrisNotificationReceiptV1 notificationReceipt(
            String state,
            String channel,
            UUID notificationId,
            String materializedDigest,
            String providerReceiptDigest,
            String errorCode,
            boolean retryable,
            Instant nextAttemptAt) {
        return notificationReceipt(
                state, channel, notificationId, materializedDigest,
                providerReceiptDigest, errorCode, retryable, nextAttemptAt, 1);
    }

    private static HrisNotificationReceiptV1 notificationReceipt(
            String state,
            String channel,
            UUID notificationId,
            String materializedDigest,
            String providerReceiptDigest,
            String errorCode,
            boolean retryable,
            Instant nextAttemptAt,
            int attemptNumber) {
        return new HrisNotificationReceiptV1(
                ID_3,
                ID_1,
                ID_2,
                1L,
                11L,
                1L,
                state,
                channel,
                "POLICY_ALLOWED",
                null,
                notificationId,
                1L,
                1L,
                materializedDigest,
                providerReceiptDigest,
                attemptNumber,
                Instant.parse("2026-09-11T00:01:00Z"),
                errorCode,
                retryable,
                nextAttemptAt,
                ID_3);
    }

    private static HrisAuditOutboxEventV1 auditEvent(String outcome, String errorCode) {
        return new HrisAuditOutboxEventV1(
                ID_1,
                1L,
                Instant.parse("2026-09-11T00:00:00Z"),
                "dwp.people",
                "hris.hrm",
                "WORKER",
                "worker:11",
                1L,
                "WORKER_UPDATED",
                outcome,
                "USER",
                "actor:1",
                "HRIS.WORKER.UPDATE",
                "EMPLOYMENT_ADMINISTRATION",
                "decision:1",
                1L,
                1L,
                null,
                null,
                null,
                null,
                "worker:11",
                null,
                errorCode,
                null,
                DIGEST,
                DIGEST,
                "RESTRICTED",
                "EXTENDED",
                ID_2,
                ID_3);
    }

    private static Class<?> componentType(Class<?> recordType, String componentName) {
        return List.of(recordType.getRecordComponents()).stream()
                .filter(component -> component.getName().equals(componentName))
                .findFirst()
                .orElseThrow()
                .getType();
    }
}
