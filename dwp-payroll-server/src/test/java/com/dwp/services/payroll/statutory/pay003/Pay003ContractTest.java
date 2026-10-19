package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.payroll.statutory.pay003.Pay003TestFixtures.MAPPER;
import static com.dwp.services.payroll.statutory.pay003.Pay003TestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class Pay003ContractTest {

    private static final UUID CORRELATION =
            UUID.fromString("00000000-0000-0000-0000-000000000399");

    @Test
    void admittedRoutesAndResolvedCapabilitiesAreExact() {
        assertThat(Pay003Route.resolve(
                "GET", "/v1/workbench/configuration/statutory",
                "route.hcm.configuration.statutory.page"))
                .contains(Pay003Route.STATUTORY);
        assertThat(Pay003Route.resolve(
                "GET", "/v1/workbench/configuration/statutory",
                "route.hcm.configuration.statutory.data"))
                .contains(Pay003Route.STATUTORY);
        assertThat(Pay003Route.resolve(
                "GET", "/v1/workbench/pay-tax-social-insurance",
                "route.hcm.operations.pay-tax-social-insurance.page"))
                .contains(Pay003Route.TAX_SOCIAL_INSURANCE);
        assertThat(Pay003Route.resolve(
                "POST", "/v1/country-packs/" + Pay003TestFixtures.PACK
                        + "/activate",
                "route.hcm.operations.country-pack-activate.action"))
                .contains(Pay003Route.ACTIVATE);
        assertThat(Pay003Route.STATUTORY.capability())
                .isEqualTo("CONFIG.HRIS_WORKBENCH:VIEW");
        assertThat(Pay003Route.TAX_SOCIAL_INSURANCE.capability())
                .isEqualTo("DATA.HRIS_PAYROLL_STATUS:VIEW");
        assertThat(Pay003Route.ACTIVATE.capability())
                .isEqualTo("ACTION.HRIS_STATUTORY_FILING:EXECUTE");
        assertThat(Pay003Route.resolve(
                "GET", "/v1/workbench/configuration/statutory",
                "pay003.iaq-026.data.v1")).isEmpty();
    }

    @Test
    void querySchemaAcceptsFrontendDeepObjectFiltersAndRejectsAmbiguity() {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        filters.put("filter[countryPackMetadata][keys]", List.of("GB.NI"));
        filters.put("filter[statutoryRule][policyKeys]", List.of("gb.ni"));
        filters.put("filter[statutoryRule][minimumRevision]", List.of("9"));
        filters.put("filter[effectiveOn]", List.of("2026-10-19"));
        Pay003Models.QueryRequest request = new Pay003Models.QueryRequest(
                "STATUTORY", NOW, null, filters, 50, "en-GB",
                List.of("countryPackMetadata:ASC", "itemPublicId:ASC"),
                "Europe/London", CORRELATION);
        assertThat(request.filter()).containsKeys(
                "filter[countryPackMetadata][keys]",
                "filter[statutoryRule][policyKeys]",
                "filter[effectiveOn]");

        assertThatThrownBy(() -> queryWith(
                Map.of("filter[effectiveOn]", List.of("2026-2-3")),
                List.of("itemPublicId:ASC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queryWith(
                Map.of("filter[statutoryRule][minimumRevision]",
                        List.of("999999999999999999999999999999")),
                List.of("itemPublicId:ASC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(queryWith(Map.of(), List.of(
                "itemPublicId:ASC", "itemPublicId:DESC")).sort())
                .containsExactly("itemPublicId:ASC", "itemPublicId:DESC");
        assertThatThrownBy(() -> queryWith(Map.of(), List.of(
                "countryPackMetadata:ASC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queryWith(Map.of(), List.of("garbage")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cursorIsBoundSignedBoundedAndTamperEvident() {
        Pay003Canonical canonical = Pay003TestFixtures.canonical();
        Pay003Authority.Actor actor =
                Pay003TestFixtures.actor(Pay003Route.STATUTORY);
        Pay003Models.QueryRequest query = Pay003TestFixtures.query("STATUTORY");
        Map<String, Pay003Models.Decision> decisions = Map.of(
                "COUNTRY_PACK_METADATA", Pay003Models.Decision.VIEW,
                "STATUTORY_RULE", Pay003Models.Decision.MASK,
                "ACTIVATION_STATUS", Pay003Models.Decision.OMIT);
        String binding = canonical.queryBinding(
                actor, query, NOW, decisions, "CURRENT", "STATUTORY");
        String token = canonical.cursor(25, binding, NOW);

        assertThat(token).matches("[A-Za-z0-9_-]{16,1024}");
        assertThat(canonical.cursorState(token))
                .isEqualTo(new Pay003Canonical.CursorState(25, binding, NOW));
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;
        assertThatThrownBy(() -> canonical.cursorState(tampered))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void canonicalHashesIgnoreMapAndProviderSourceOrdering() throws Exception {
        Pay003Canonical canonical = Pay003TestFixtures.canonical();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", 1);
        first.put("a", 2);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 2);
        second.put("z", 1);
        assertThat(canonical.digest(first)).isEqualTo(canonical.digest(second));

        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("f0000000-0000-0000-0000-000000000001");
        Pay003Models.ProviderSourceLine line1 = line(low, "4.2500");
        Pay003Models.ProviderSourceLine line2 = line(high, "5.7500");
        Pay003SocialInsuranceOutcomePublisher publisher = publisher(canonical);
        Pay003Models.PreparedSocialInsuranceFact one = publisher.prepare(
                outcome(List.of(line1, line2)));
        Pay003Models.PreparedSocialInsuranceFact two = publisher.prepare(
                outcome(List.of(line2, line1)));
        assertThat(one).isEqualTo(two);
        assertThat(one.sourceResultLineIds()).containsExactly(low, high);
        assertHttpActivationBodyClosed();
        assertThat(new Pay003Models.QueryRequest(
                "TAX_SOCIAL_INSURANCE", NOW, null,
                Map.of(
                        "filter[socialInsurance][minimumAmount]",
                        List.of("-1.123456"),
                        "filter[policyVersion][policyKeys]", List.of(),
                        "filter[effectiveOn]", List.of("2026-10-19")),
                25, "en-GB",
                List.of("socialInsurance:ASC", "socialInsurance:DESC",
                        "itemPublicId:ASC"),
                "Europe/London", CORRELATION).filter())
                .containsEntry("filter[policyVersion][policyKeys]", List.of());
        assertThat(java.lang.reflect.Modifier.isFinal(
                Pay003Service.class.getModifiers())).isFalse();
        assertThat(Pay003Service.class.getDeclaredMethod(
                "activate", Pay003Authority.Actor.class, UUID.class,
                Pay003Models.ActivationCommand.class,
                Pay003Models.ActivationHeaders.class)
                .isAnnotationPresent(
                        org.springframework.transaction.annotation
                                .Transactional.class)).isTrue();
    }

    @Test
    void typedOutcomeAndMoneyBoundsRejectRelabelAndRounding() {
        assertThatThrownBy(() -> new Pay003Models.ProviderSourceLine(
                UUID.randomUUID(), new Pay003Models.PostedMoney(
                new BigDecimal("1.0000"), "GBP"), "DEDUCTION"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pay003Models.MoneyValue("-0.0000", "GBP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pay003Models.MoneyValue(
                "1000000000000000", "GBP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pay003Models.MoneyValue("1.00000", "GBP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Pay003Models.MoneyValue(
                "999999999999999.9999", "GBP").amount())
                .isEqualTo("999999999999999.9999");

        Pay003SocialInsuranceOutcomePublisher publisher =
                publisher(Pay003TestFixtures.canonical());
        assertThatThrownBy(() -> publisher.prepare(new Pay003Models
                .SocialInsuranceProviderOutcome(
                1, UUID.randomUUID(), 1, UUID.randomUUID(),
                Pay003TestFixtures.PACK, 4, "policy-v1",
                new Pay003Models.PostedMoney(new BigDecimal("10.0000"), "GBP"),
                List.of(line(UUID.randomUUID(), "9.9999")),
                Pay003TestFixtures.DIGEST_A, NOW)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void successWireMatchesAcceptedNestedIaqAndReceiptSchemas() {
        Pay003Models.EntryQueryResponse response = emptyResponse(
                "2", "STATUTORY", CORRELATION);
        JsonNode json = MAPPER.valueToTree(response);
        assertThat(json.fieldNames()).toIterable().containsExactly(
                "projectionVersion", "asOf", "variantKey", "queryState", "items");
        assertThat(json.get("queryState").fieldNames()).toIterable().containsExactly(
                "state", "scopeDecisionRevision", "freshness", "nextCursor",
                "fieldDecisionSummary", "partialFailures", "correlationId",
                "cachePolicy");

        Pay003Models.PayrollCommandReceipt receipt =
                new Pay003Models.PayrollCommandReceipt(
                        UUID.randomUUID(), "COUNTRY_PACK_ACTIVATE",
                        Pay003Models.ACTIVATE_ACTION, Pay003TestFixtures.SUBJECT,
                        Pay003TestFixtures.DIGEST_A, 17,
                        Pay003Models.ACTIVATION_PURPOSE, 23, "SUCCEEDED",
                        CORRELATION, Pay003TestFixtures.DIGEST_B, NOW, 4,
                        "country-pack:" + Pay003TestFixtures.PACK);
        assertThat(MAPPER.valueToTree(receipt).fieldNames()).toIterable()
                .containsExactly(
                        "receiptId", "commandType", "originatingAction",
                        "subjectPrincipalPublicId", "populationScopeDigest",
                        "fieldPolicyRevision", "purposeCode",
                        "authorizationRevision", "status", "correlationId",
                        "requestDigest", "createdAt", "aggregateVersion",
                        "resultRef");
    }

    @Test
    void activationBodyIsExactlyFourRequiredFieldsEvenWithLenientMapper()
            throws Exception {
        String body = """
                {"officialReferenceSet":"UK-REF-2026",
                 "effectiveDate":"2026-10-01",
                 "goldenPackDigest":"%s",
                 "statutoryApprovalReceiptId":"%s"}
                """.formatted(Pay003TestFixtures.DIGEST_A,
                Pay003TestFixtures.APPROVAL);
        com.fasterxml.jackson.databind.ObjectMapper lenient = MAPPER.copy()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES);
        assertThat(lenient.readValue(
                body, Pay003Models.ActivationCommand.class).effectiveDate())
                .isEqualTo(LocalDate.parse("2026-10-01"));
        assertThatThrownBy(() -> lenient.readValue(
                body.replace("}", ",\"clientClaim\":\"forbidden\"}"),
                Pay003Models.ActivationCommand.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        assertThatThrownBy(() -> lenient.readValue(
                body.replace("\"officialReferenceSet\":\"UK-REF-2026\",", ""),
                Pay003Models.ActivationCommand.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        assertThatThrownBy(() -> lenient.readValue(
                body.replace("\"UK-REF-2026\"", "null"),
                Pay003Models.ActivationCommand.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    private void assertHttpActivationBodyClosed() throws Exception {
        Pay003Service service = mock(Pay003Service.class);
        Pay003ScopeResolver scopes = mock(Pay003ScopeResolver.class);
        when(scopes.resolve(any())).thenReturn(
                Pay003TestFixtures.actor(Pay003Route.ACTIVATE).scope());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new Pay003Controller(service, scopes))
                .addFilters(new Pay003SecurityFilter(
                        "service-secret", "1", MAPPER,
                        Clock.fixed(NOW, ZoneOffset.UTC)))
                .setControllerAdvice(new Pay003ProblemAdvice())
                .build();
        String valid = """
                {"officialReferenceSet":"UK-REF-2026",
                 "effectiveDate":"2026-10-01",
                 "goldenPackDigest":"%s",
                 "statutoryApprovalReceiptId":"%s"}
                """.formatted(Pay003TestFixtures.DIGEST_A,
                Pay003TestFixtures.APPROVAL);

        mvc.perform(action(post("/v1/country-packs/"
                        + Pay003TestFixtures.PACK + "/activate"))
                        .contentType("application/json")
                        .content(valid.replace("}",
                                ",\"clientClaim\":\"forbidden\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(action(post("/v1/country-packs/"
                        + Pay003TestFixtures.PACK + "/activate"))
                        .contentType("application/json")
                        .content(valid.replace(
                                "\"officialReferenceSet\":\"UK-REF-2026\",", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(action(post("/v1/country-packs/"
                        + Pay003TestFixtures.PACK + "/activate"))
                        .contentType("application/json")
                        .content(valid.replace("\"UK-REF-2026\"", "null")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void httpBindingAcceptsFrontendSerializedQueriesForBothVariants()
            throws Exception {
        Pay003Service service = mock(Pay003Service.class);
        Pay003ScopeResolver scopes = mock(Pay003ScopeResolver.class);
        when(scopes.resolve(any())).thenReturn(
                Pay003TestFixtures.actor(Pay003Route.STATUTORY).scope());
        when(service.statutory(any(), any())).thenReturn(
                emptyResponse("2", "STATUTORY", CORRELATION));
        when(service.socialInsurance(any(), any())).thenReturn(
                emptyResponse("1", "TAX_SOCIAL_INSURANCE", CORRELATION));
        Pay003SecurityFilter filter = new Pay003SecurityFilter(
                "service-secret", "1", MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new Pay003Controller(service, scopes))
                .addFilters(filter)
                .setControllerAdvice(new Pay003ProblemAdvice())
                .build();

        mvc.perform(read(get("/v1/workbench/configuration/statutory")
                        .queryParam("filter[countryPackMetadata][keys]", "GB.NI")
                        .queryParam("filter[statutoryRule][policyKeys]", "gb.ni")
                        .queryParam("sort", "countryPackMetadata:ASC")
                        .queryParam("sort", "itemPublicId:ASC"),
                Pay003Route.STATUTORY,
                "route.hcm.configuration.statutory.page"))
                .andExpect(status().isOk());
        mvc.perform(read(get("/v1/workbench/pay-tax-social-insurance")
                        .queryParam("filter[socialInsurance][currency]", "GBP")
                        .queryParam("filter[policyVersion][policyKeys]", "policy-v1")
                        .queryParam("sort", "socialInsurance:DESC")
                        .queryParam("sort", "itemPublicId:ASC"),
                Pay003Route.TAX_SOCIAL_INSURANCE,
                "route.hcm.operations.pay-tax-social-insurance.data"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pay003Models.QueryRequest> statutory =
                ArgumentCaptor.forClass(Pay003Models.QueryRequest.class);
        verify(service).statutory(any(), statutory.capture());
        assertThat(statutory.getValue().sort()).containsExactly(
                "countryPackMetadata:ASC", "itemPublicId:ASC");
        assertThat(statutory.getValue().filter())
                .containsEntry("filter[statutoryRule][policyKeys]", List.of("gb.ni"));
        ArgumentCaptor<Pay003Models.QueryRequest> social =
                ArgumentCaptor.forClass(Pay003Models.QueryRequest.class);
        verify(service).socialInsurance(any(), social.capture());
        assertThat(social.getValue().filter())
                .containsEntry("filter[policyVersion][policyKeys]",
                        List.of("policy-v1"));
    }

    @Test
    void filterReturnsClosedProblemTaxonomyAndCorrelation() throws Exception {
        Pay003SecurityFilter filter = new Pay003SecurityFilter(
                "service-secret", "1", MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletRequest mismatch = activationRequest();
        mismatch.removeHeader("If-Match");
        mismatch.addHeader("If-Match", "W/\"3\"");
        MockHttpServletResponse conflict = new MockHttpServletResponse();
        filter.doFilterInternal(mismatch, conflict, unusedChain());
        assertThat(conflict.getStatus()).isEqualTo(409);
        assertThat(conflict.getHeader("X-Correlation-ID"))
                .isEqualTo(CORRELATION.toString());
        assertThat(MAPPER.readTree(conflict.getContentAsByteArray())
                .get("correlationId").asText()).isEqualTo(CORRELATION.toString());

        MockHttpServletRequest noStepUp = activationRequest();
        noStepUp.removeHeader("X-DWP-Step-Up-Challenge");
        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        filter.doFilterInternal(noStepUp, forbidden, unusedChain());
        assertThat(forbidden.getStatus()).isEqualTo(403);

        MockHttpServletRequest split = readRequest(Pay003Route.STATUTORY,
                "route.hcm.configuration.statutory.data");
        split.removeHeader("X-Correlation-ID");
        split.addHeader("X-Correlation-ID", UUID.randomUUID().toString());
        MockHttpServletResponse bad = new MockHttpServletResponse();
        filter.doFilterInternal(split, bad, unusedChain());
        assertThat(bad.getStatus()).isEqualTo(400);
    }

    @Test
    void authorityNegativeMatrixRejectsCallerClaimsBeforeController() throws Exception {
        Map<String, String> changes = Map.of(
                "X-Purpose-Code", "WRONG_PURPOSE",
                "X-DWP-Current-Revalidate-At", NOW.minusSeconds(1).toString(),
                "X-DWP-Field-Policy-Key", "wrong-policy",
                "X-DWP-Population-Kind", "LEGAL_ENTITY",
                "X-DWP-Service-Token", "wrong-secret",
                "X-DWP-Tenant-ID", "2");
        Pay003SecurityFilter filter = new Pay003SecurityFilter(
                "service-secret", "1", MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        for (var change : changes.entrySet()) {
            MockHttpServletRequest request = readRequest(Pay003Route.STATUTORY,
                    "route.hcm.configuration.statutory.data");
            request.removeHeader(change.getKey());
            request.addHeader(change.getKey(), change.getValue());
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, unusedChain());
            assertThat(response.getStatus()).isEqualTo(
                    "X-DWP-Tenant-ID".equals(change.getKey()) ? 404 : 503);
            assertThat(response.getContentAsString()).doesNotContain(
                    Pay003TestFixtures.PACK.toString(), "activationRevision");
        }
        for (String header : List.of("X-Tenant-Override", "X-Actor-Override",
                "X-Legal-Entity-ID", "X-Pay-Group-ID", "X-Caller-Tenant-ID")) {
            MockHttpServletRequest request = activationRequest();
            request.addHeader(header, UUID.randomUUID().toString());
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, unusedChain());
            assertThat(response.getStatus()).isEqualTo(403);
        }
        MockHttpServletRequest staleDecision = activationRequest();
        staleDecision.removeHeader("X-DWP-Expected-Decision-Revision");
        staleDecision.addHeader("X-DWP-Expected-Decision-Revision", "old-decision");
        MockHttpServletResponse stale = new MockHttpServletResponse();
        filter.doFilterInternal(staleDecision, stale, unusedChain());
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(Pay003Route.resolve("POST", "/v1/country-packs/"
                        + Pay003TestFixtures.PACK.toString().toUpperCase(java.util.Locale.ROOT)
                        .replace("000000000304", "ABCDEF000304") + "/activate",
                "route.hcm.operations.country-pack-activate.action")).isEmpty();
        assertThatThrownBy(() -> queryWith(Map.of(), List.of("garbage")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Pay003Models.QueryRequest queryWith(
            Map<String, List<String>> filter, List<String> sort) {
        return new Pay003Models.QueryRequest(
                "STATUTORY", NOW, null, filter, 25, "en-GB", sort,
                "Europe/London", CORRELATION);
    }

    private Pay003Models.ProviderSourceLine line(UUID id, String amount) {
        return new Pay003Models.ProviderSourceLine(
                id, new Pay003Models.PostedMoney(
                new BigDecimal(amount), "GBP"),
                "TYPED_COUNTRY_RULE_OUTPUT");
    }

    private Pay003Models.SocialInsuranceProviderOutcome outcome(
            List<Pay003Models.ProviderSourceLine> lines) {
        return new Pay003Models.SocialInsuranceProviderOutcome(
                1, UUID.fromString("00000000-0000-0000-0000-000000000351"),
                1, UUID.fromString("00000000-0000-0000-0000-000000000352"),
                Pay003TestFixtures.PACK, 4, "policy-v1",
                new Pay003Models.PostedMoney(new BigDecimal("10.0000"), "GBP"),
                lines, Pay003TestFixtures.DIGEST_C, NOW);
    }

    private Pay003SocialInsuranceOutcomePublisher publisher(
            Pay003Canonical canonical) {
        org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:not-used");
        return new Pay003SocialInsuranceOutcomePublisher(
                new org.springframework.jdbc.core.namedparam
                        .NamedParameterJdbcTemplate(dataSource), canonical);
    }

    private Pay003Models.EntryQueryResponse emptyResponse(
            String version, String variant, UUID correlation) {
        return new Pay003Models.EntryQueryResponse(
                version, NOW, variant,
                new Pay003Models.QueryStateValue(
                        Pay003Models.QueryState.EMPTY, "scope-11", "CURRENT",
                        null, new Pay003Models.FieldDecisionSummary(0, 0, 0),
                        List.of(), correlation,
                        new Pay003Models.CachePolicy(
                                "decision-41",
                                "dwp.hris.access.assignment.changed.v1", 0)),
                List.of());
    }

    private MockHttpServletRequestBuilder read(
            MockHttpServletRequestBuilder request,
            Pay003Route route,
            String routeKey) {
        return request
                .queryParam("limit", "25")
                .queryParam("locale", "en-GB")
                .queryParam("timeZone", "Europe/London")
                .queryParam("correlationId", CORRELATION.toString())
                .header("Authorization", "Bearer gateway-user")
                .header("X-DWP-Service-Token", "service-secret")
                .header("X-DWP-Tenant-ID", "1")
                .header("X-DWP-User-ID", "91")
                .header("X-DWP-Subject-Principal-Public-ID",
                        Pay003TestFixtures.SUBJECT.toString())
                .header("X-DWP-Permissions",
                        "APP.HCM:VIEW," + route.capability())
                .header("X-DWP-Route-Contract-Key", routeKey)
                .header("X-DWP-Current-Decision-Revision", "decision-41")
                .header("X-DWP-Current-Revalidate-At",
                        NOW.plusSeconds(600).toString())
                .header("X-DWP-Context-Key", "ctx-pay003")
                .header("X-DWP-Context-Scope-Key", scopeKey(route))
                .header("X-DWP-Active-Access-Mode", "NORMAL")
                .header("X-DWP-Field-Policy-Revision", "17")
                .header("X-DWP-Authorization-Revision", "23")
                .header("X-Purpose-Code", route.purpose())
                .header("X-DWP-Population-Kind", route.populationKind())
                .header("X-DWP-Field-Policy-Key", route.fieldPolicyKey())
                .header("X-Correlation-ID", CORRELATION.toString());
    }

    private MockHttpServletRequestBuilder action(
            MockHttpServletRequestBuilder request) {
        Pay003Route route = Pay003Route.ACTIVATE;
        return request
                .header("Authorization", "Bearer gateway-user")
                .header("X-DWP-Service-Token", "service-secret")
                .header("X-DWP-Tenant-ID", "1")
                .header("X-DWP-User-ID", "91")
                .header("X-DWP-Subject-Principal-Public-ID",
                        Pay003TestFixtures.SUBJECT.toString())
                .header("X-DWP-Permissions",
                        "APP.HCM:VIEW," + route.capability())
                .header("X-DWP-Responsibilities",
                        "HRIS_DUTY_STATUTORY_OPERATOR")
                .header("X-DWP-Route-Contract-Key",
                        "route.hcm.operations.country-pack-activate.action")
                .header("X-DWP-Current-Decision-Revision", "decision-41")
                .header("X-DWP-Current-Revalidate-At",
                        NOW.plusSeconds(600).toString())
                .header("X-DWP-Context-Key", "ctx-pay003")
                .header("X-DWP-Context-Scope-Key", scopeKey(route))
                .header("X-DWP-Active-Access-Mode", "ELEVATED")
                .header("X-DWP-Field-Policy-Revision", "17")
                .header("X-DWP-Authorization-Revision", "23")
                .header("X-Purpose-Code", route.purpose())
                .header("X-DWP-Population-Kind", route.populationKind())
                .header("X-DWP-Expected-Object-Version", "3")
                .header("If-Match", "\"3\"")
                .header("X-DWP-Expected-Decision-Revision", "decision-41")
                .header("X-DWP-Step-Up-Challenge", "signed-challenge")
                .header("Idempotency-Key", "pay003-idempotency-0001")
                .header("X-Correlation-ID", CORRELATION.toString());
    }

    private MockHttpServletRequest readRequest(
            Pay003Route route, String routeKey) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", route == Pay003Route.STATUTORY
                ? "/v1/workbench/configuration/statutory"
                : "/v1/workbench/pay-tax-social-insurance");
        request.setParameter("filter", "{}");
        request.setParameter("limit", "25");
        request.setParameter("locale", "en-GB");
        request.setParameter("sort", "itemPublicId:ASC");
        request.setParameter("timeZone", "Europe/London");
        request.setParameter("correlationId", CORRELATION.toString());
        authorityHeaders(request, route, routeKey);
        return request;
    }

    private MockHttpServletRequest activationRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/country-packs/" + Pay003TestFixtures.PACK
                + "/activate");
        authorityHeaders(request, Pay003Route.ACTIVATE,
                "route.hcm.operations.country-pack-activate.action");
        request.addHeader("X-DWP-Responsibilities",
                "HRIS_DUTY_STATUTORY_OPERATOR");
        request.addHeader("X-DWP-Expected-Object-Version", "3");
        request.addHeader("If-Match", "\"3\"");
        request.addHeader("X-DWP-Expected-Decision-Revision", "decision-41");
        request.addHeader("X-DWP-Step-Up-Challenge", "signed-challenge");
        request.addHeader("Idempotency-Key", "pay003-idempotency-0001");
        return request;
    }

    private void authorityHeaders(
            MockHttpServletRequest request,
            Pay003Route route,
            String routeKey) {
        request.addHeader("Authorization", "Bearer gateway-user");
        request.addHeader("X-DWP-Service-Token", "service-secret");
        request.addHeader("X-DWP-Tenant-ID", "1");
        request.addHeader("X-DWP-User-ID", "91");
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                Pay003TestFixtures.SUBJECT.toString());
        request.addHeader("X-DWP-Permissions",
                "APP.HCM:VIEW," + route.capability());
        request.addHeader("X-DWP-Route-Contract-Key", routeKey);
        request.addHeader("X-DWP-Current-Decision-Revision", "decision-41");
        request.addHeader("X-DWP-Current-Revalidate-At",
                NOW.plusSeconds(600).toString());
        request.addHeader("X-DWP-Context-Key", "ctx-pay003");
        request.addHeader("X-DWP-Context-Scope-Key", scopeKey(route));
        request.addHeader("X-DWP-Active-Access-Mode",
                route.highRisk() ? "ELEVATED" : "NORMAL");
        request.addHeader("X-DWP-Field-Policy-Revision", "17");
        request.addHeader("X-DWP-Authorization-Revision", "23");
        request.addHeader("X-Purpose-Code", route.purpose());
        request.addHeader("X-DWP-Population-Kind", route.populationKind());
        if (route.fieldPolicyKey() != null) {
            request.addHeader("X-DWP-Field-Policy-Key",
                    route.fieldPolicyKey());
        }
        request.addHeader("X-Correlation-ID", CORRELATION.toString());
    }

    private String scopeKey(Pay003Route route) {
        return HcmEligibilityScopeKey.derived(
                1, 91, route.name(), "source-scope", "rel-1", "pop-1");
    }

    private FilterChain unusedChain() {
        return (request, response) -> {
            throw new AssertionError("closed request reached the controller");
        };
    }
}
