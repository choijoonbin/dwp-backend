package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalSignatureControllerTest {
    final UUID request=UUID.randomUUID();
    ObjectMapper mapper;
    @BeforeEach void setup() {
        var builder=new Jackson2ObjectMapperBuilder().featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        new ApprovalSignatureJsonConfiguration().approvalSignatureStrictInputCustomizer().customize(builder); mapper=builder.build();
        ApprovalRequestContext.set(99L,42L,UUID.randomUUID(),Set.of("WORKSPACE_USER"),Set.of());
    }
    @AfterEach void clear() { ApprovalRequestContext.clear(); }
    MockMvc mvc(ApprovalSignatureService service) {
        return MockMvcBuilders.standaloneSetup(new ApprovalSignatureController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
    }
    String create() { return "{\"expectedVersion\":5,\"signerKind\":\"SELF_ATTESTATION\",\"locale\":\"ko\",\"sourceDigest\":\""+"a".repeat(64)+"\",\"idempotencyKey\":\"original-key\"}"; }
    @Test void realControllerWithMissingInstalledAuthorityRejectsCallerWindowAndTouchesNoSql() throws Exception {
        var source=mock(ApprovalSignatureSourceRepository.class); var ledger=mock(ApprovalSignatureRepository.class);
        var json=new ApprovalSignatureCanonical(mapper);
        var authority=new ApprovalSignatureAuthority(null,new com.nimbusds.jose.jwk.JWKSet(),Clock.systemUTC(),json);
        var service=new ApprovalSignatureService(true,authority,source,ledger,null,null,json,null,null,Clock.systemUTC());
        mvc(service).perform(post("/v1/requests/"+request+"/signature-requests").contentType(MediaType.APPLICATION_JSON).content(create())
                .header("X-DWP-Signature-Authority","{\"installed\":true}").header("X-DWP-Expected-Object-Version","5"))
                .andExpect(status().isServiceUnavailable());
        mvc(service).perform(get("/v1/requests/"+request+"/signature-context")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(source,ledger);
    }
    @Test void actualSpringMapperRejectsUnknownSignerAssignmentsBeforeFacadeAndDoesNotChangeOtherDtoPolicy() throws Exception {
        var service=mock(ApprovalSignatureService.class); var mvc=mvc(service);
        for (String field:java.util.List.of("signerUserId","delegationId","providerUrl","authorityWindow","signatureBytes")) {
            String payload=create().substring(0,create().length()-1)+",\""+field+"\":100}";
            mvc.perform(post("/v1/requests/"+request+"/signature-requests").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/v1/requests/"+request+"/signature-requests").contentType(MediaType.APPLICATION_JSON).content(create().replace("SELF_ATTESTATION","DELEGATED"))).andExpect(status().isBadRequest());
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
        assertThat(mapper.readValue("{\"known\":1,\"extra\":true}",Unrelated.class).known()).isEqualTo(1);
        verifyNoInteractions(service);
    }
    record Unrelated(int known) { }
    @Test void exactVersionsAndConsentRejectFractionOverflowStringsAndCoercionBeforeFacade() throws Exception {
        var service=mock(ApprovalSignatureService.class); var mvc=mvc(service);
        for (String value:java.util.List.of("5.5","5.0","\"5\"","9007199254740992","18446744073709551621"))
            mvc.perform(post("/v1/requests/"+request+"/signature-requests").contentType(MediaType.APPLICATION_JSON)
                    .content(create().replace("\"expectedVersion\":5","\"expectedVersion\":"+value))).andExpect(status().isBadRequest());
        String consent="{\"expectedVersion\":0,\"sourceDigest\":\""+"a".repeat(64)+"\",\"termsId\":\"TERMS\",\"termsVersion\":1,\"termsSha256\":\""+"a".repeat(64)+"\",\"locale\":\"ko\",\"accepted\":true,\"idempotencyKey\":\"consent\"}";
        for (String value:java.util.List.of("1.5","1.0","\"1\"","18446744073709551617"))
            mvc.perform(post("/v1/signature-requests/"+request+"/consents").contentType(MediaType.APPLICATION_JSON)
                    .content(consent.replace("\"termsVersion\":1","\"termsVersion\":"+value))).andExpect(status().isBadRequest());
        for (String value:java.util.List.of("\"true\"","1","\"false\""))
            mvc.perform(post("/v1/signature-requests/"+request+"/consents").contentType(MediaType.APPLICATION_JSON)
                    .content(consent.replace("\"accepted\":true","\"accepted\":"+value))).andExpect(status().isBadRequest());
        assertThat(mapper.readValue(consent.replace(",\"accepted\":true",""),ApprovalSignatureDtos.Consent.class).accepted()).isFalse();
        verifyNoInteractions(service);
    }
    @Test void defaultFeatureFlagDoesNotRegisterServiceOrControllerWithoutAnyDatasource() {
        try (var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(ApprovalSignatureConfiguration.class,ApprovalSignatureController.class); context.refresh();
            assertThat(context.getBeansOfType(ApprovalSignatureService.class)).isEmpty();
            assertThat(context.getBeansOfType(ApprovalSignatureController.class)).isEmpty();
        }
    }
    @Test void authorityRouteSubstitutionViaRealControllerIsRejectedBeforeSql() throws Exception {
        var source=mock(ApprovalSignatureSourceRepository.class); var ledger=mock(ApprovalSignatureRepository.class);
        var json=new ApprovalSignatureCanonical(mapper); var port=new ApprovalSignatureTestAuthority(Clock.systemUTC());
        port.change=b -> b.claim("path","/v1/tasks/"+request+"/approve");
        var service=new ApprovalSignatureService(true,port.verifier(json),source,ledger,null,null,json,null,null,Clock.systemUTC());
        mvc(service).perform(post("/v1/requests/"+request+"/signature-requests").contentType(MediaType.APPLICATION_JSON).content(create())).andExpect(status().isForbidden());
        verifyNoInteractions(source,ledger);
    }
}
