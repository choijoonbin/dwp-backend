package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.*;
import com.dwp.services.approval.signatures.ApprovalSignatureCommandReceiptDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class ApprovalSignatureSourceFailureTest {
    final ApprovalSignatureCanonical json=new ApprovalSignatureCanonical(new ObjectMapper());
    @Test void defaultDisabledSignatureSourceNeverReadsRequestKeysTransportOrSql(){
        var jdbc=mock(NamedParameterJdbcTemplate.class);var high=mock(ApprovalStepUpVerifier.class);var installed=mock(ApprovalSignatureInstalledSource.class);
        var source=new ApprovalSignatureVerifiedSourceSupplier(false,()->{throw new AssertionError("request");},installed,
                ()->{throw new AssertionError("keys");},()->{throw new AssertionError("http");},()->{throw new AssertionError("owner");},jdbc,json,high,Clock.systemUTC());
        var b=new Binding(Operation.GET,UUID.randomUUID(),null,json.digest(Map.of()),null,json.read("{}",com.fasterxml.jackson.databind.JsonNode.class));
        assertThatThrownBy(()->source.currentSignedAssertion(b,UUID.randomUUID())).isInstanceOf(BaseException.class);verifyNoInteractions(jdbc,high,installed);
    }
    @Test void missingSealedSource10IsDeniedBeforeSignatureNativeSqlOrKeys(){
        var jdbc=mock(NamedParameterJdbcTemplate.class);var high=mock(ApprovalStepUpVerifier.class);var installed=mock(ApprovalSignatureInstalledSource.class);
        var b=new Binding(Operation.GET,UUID.randomUUID(),null,json.digest(Map.of()),null,json.read("{}",com.fasterxml.jackson.databind.JsonNode.class));var r=new MockHttpServletRequest();
        when(installed.capture(r,b)).thenThrow(ApprovalSignatureCanonical.unavailable());
        var source=new ApprovalSignatureVerifiedSourceSupplier(true,()->r,installed,()->{throw new AssertionError("keys");},()->{throw new AssertionError("http");},
                ()->{throw new AssertionError("owner");},jdbc,json,high,Clock.systemUTC());
        assertThatThrownBy(()->source.currentSignedAssertion(b,UUID.randomUUID())).isInstanceOf(BaseException.class);verifyNoInteractions(jdbc,high);
    }
    @Test void metadataDefaultDisabledOrUninstalledNeverFetchesArtifactOrCommands(){
        var repository=mock(ApprovalSignatureCommandReceiptRepository.class);var installed=mock(ApprovalSignatureReceiptInstalledSource.class);var r=new MockHttpServletRequest();
        var q=new Query("original-key",OriginalOperation.SIGN,UUID.randomUUID(),"a".repeat(64));
        var disabled=new ApprovalSignatureCommandReceiptSource(false,()->{throw new AssertionError("request");},installed,()->{throw new AssertionError("keys");},
                ()->{throw new AssertionError("http");},repository,json,Clock.systemUTC());
        assertThatThrownBy(()->disabled.assertion(q,UUID.randomUUID())).isInstanceOf(BaseException.class);verifyNoInteractions(repository,installed);
        when(installed.capture(r,q)).thenThrow(ApprovalSignatureCanonical.unavailable());
        var missing=new ApprovalSignatureCommandReceiptSource(true,()->r,installed,()->{throw new AssertionError("keys");},()->{throw new AssertionError("http");},repository,json,Clock.systemUTC());
        assertThatThrownBy(()->missing.assertion(q,UUID.randomUUID())).isInstanceOf(BaseException.class);verifyNoInteractions(repository);
    }
}
