package com.dwp.services.approval.informationreplay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.domain.ApprovalInformationReceiptSource;
import com.dwp.services.approval.security.*;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

class InformationReceiptControllerContractTest {
    final UUID requestId=UUID.randomUUID();
    final String path="/v1/requests/"+requestId+"/information-commands/original-info/receipt";
    final ApprovalInformationReceiptSource source=mock(ApprovalInformationReceiptSource.class);
    final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    final InformationReplayProofIssuer issuer=mock(InformationReplayProofIssuer.class);
    final InformationReplayAuthorityClient client=mock(InformationReplayAuthorityClient.class);
    final InformationReplayRuntime runtime=new InformationReplayRuntime(issuer,client);
    MockMvc mvc(boolean enabled) {
        var facade=new InformationReceiptFacade(enabled,()->runtime,new InformationReceiptInstalledContext(),source,transactions,Clock.systemUTC());
        return MockMvcBuilders.standaloneSetup(new InformationReceiptController(facade))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
    }
    MockHttpServletRequestBuilder admitted(String url,String body) {
        ApprovalRequestContext.set(100L,42L,UUID.randomUUID(),Set.of("FINANCE_REVIEWER"),Set.of(InformationReceiptInstalledContext.PERMISSION));
        var installed=InformationReceiptInstalledTestFixture.install(requestId,"original-info");
        return post(url).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-DWP-Active-Access-Mode","NORMAL").header("X-DWP-Identity-Plane","TENANT")
                .requestAttr(ApprovalPilotPepRegistry.class.getName()+".authorities",installed.getAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities"));
    }
    String valid() {return "{\"operation\":\"REQUEST_INFO\",\"originalBodyBase64\":\""+Base64.getEncoder().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\"}";}
    @AfterEach void clear() {InformationReceiptInstalledTestFixture.clear();}
    void noAuthorityOrSql() {verifyNoInteractions(source,transactions,issuer,client);}
    @Test void disabledActualPostIs503WithoutSqlOrAnIdempotencyHeader() throws Exception {
        mvc(false).perform(post(path).contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().isServiceUnavailable());noAuthorityOrSql();
    }
    @Test void onlyPostJsonIsAccepted() throws Exception {
        mvc(false).perform(get(path)).andExpect(status().isMethodNotAllowed());
        mvc(false).perform(post(path).contentType(MediaType.TEXT_PLAIN).content(valid())).andExpect(status().isUnsupportedMediaType());noAuthorityOrSql();
    }
    @Test void queryAndEncodedKeyAliasesAreRejectedBeforeSourceReads() throws Exception {
        mvc(true).perform(admitted(path,valid()).queryParam("tenantId","42")).andExpect(status().isForbidden());
        mvc(true).perform(admitted(path.replace("original-info","original%2dinfo"),valid())).andExpect(status().isForbidden());noAuthorityOrSql();
    }
    @Test void duplicateUnknownAndMissingLookupFieldsNeverReachSql() throws Exception {
        mvc(true).perform(admitted(path,valid().replace("REQUEST_INFO","REQUEST_INFO\",\"operation\":\"REPLY"))).andExpect(status().isForbidden());
        mvc(true).perform(admitted(path,valid().replace("}",",\"tenantId\":42}"))).andExpect(status().isForbidden());
        mvc(true).perform(admitted(path,"{\"operation\":\"REQUEST_INFO\"}")).andExpect(status().isForbidden());noAuthorityOrSql();
    }
    @Test void oversizedLookupAndUnpaddedBase64AreRejectedBeforeSqlOrRemoteAuthority() throws Exception {
        mvc(true).perform(admitted(path," ".repeat(InformationReceiptBody.LOOKUP_MAX+5000))).andExpect(status().isForbidden());
        mvc(true).perform(admitted(path,"{\"operation\":\"REQUEST_INFO\",\"originalBodyBase64\":\"e30\"}")).andExpect(status().isForbidden());noAuthorityOrSql();
    }
    @Test void receiptRecordExposesOnlyTheSevenRequiredPublicFields() throws Exception {
        var receipt=new InformationCommandReceipt("COMPLETED",UUID.randomUUID(),1,1,1,"a".repeat(64),false);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var value=json.valueToTree(receipt);assertEquals(7,value.size());
        assertFalse(value.has("tenantId"));assertFalse(value.has("principalId"));assertFalse(value.has("admission"));
    }
}
