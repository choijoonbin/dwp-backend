package com.dwp.services.approval.informationreplay;

import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.exception.BaseException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class InformationReceiptBodyTest {
    InformationReceiptBody body(String original) {return new InformationReceiptBody("REQUEST_INFO",Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8)));}
    @Test void preservesExactOriginalWhitespaceBytesAndCanonicalPaddedEncoding() {
        String raw="{ \"expectedVersion\":5,\"message\":\"Evidence\" }\n";
        assertArrayEquals(raw.getBytes(StandardCharsets.UTF_8),body(raw).originalBytes());
    }
    @Test void rejectsUnpaddedNonCanonicalOrUrlAlphabetEncoding() {
        var body=body("{}");assertThrows(BaseException.class,()->new InformationReceiptBody("REQUEST_INFO",body.originalBodyBase64().replace("=","")).originalBytes());
        for(String encoded:java.util.List.of("e30=\n","_w==","e31=","!!!!")) assertThrows(BaseException.class,()->new InformationReceiptBody("REQUEST_INFO",encoded).originalBytes());
    }
    @Test void rejectsDuplicateOuterAndNestedOriginalFieldsAndTrailingDocuments() {
        for(String raw:java.util.List.of("{\"x\":1,\"x\":2}","{\"x\":{\"id\":1,\"id\":2}}","{} {}")) assertThrows(BaseException.class,()->body(raw).originalBytes());
        assertThrows(BaseException.class,()->InformationReceiptBody.parse("{\"operation\":\"REPLY\",\"operation\":\"REQUEST_INFO\",\"originalBodyBase64\":\"e30=\"}".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void rejectsUnknownOuterCallerAuthorityFieldsAndWrongOperations() {
        assertThrows(BaseException.class,()->InformationReceiptBody.parse("{\"operation\":\"REPLY\",\"originalBodyBase64\":\"e30=\",\"tenantId\":42}".getBytes(StandardCharsets.UTF_8)));
        for(String op:new String[]{null,"APPROVE","REPLY "}) assertThrows(BaseException.class,()->new InformationReceiptBody(op,"e30=").originalBytes());
    }
    @Test void rejectsFractionalJsonButPreservesTypedDecimalStrings() {
        assertThrows(BaseException.class,()->body("{\"amount\":20.5}").originalBytes());
        assertArrayEquals("{\"amount\":\"20.5\"}".getBytes(StandardCharsets.UTF_8),body("{\"amount\":\"20.5\"}").originalBytes());
    }
    @Test void rejectsNonObjectDeepAndUnboundedOriginalPayloads() {
        for(String raw:java.util.List.of("[]","null","{\"x\":"+"[".repeat(34)+"0"+"]".repeat(34)+"}","{\"x\":\""+"x".repeat(200001)+"\"}"))
            assertThrows(BaseException.class,()->body(raw).originalBytes());
        assertThrows(BaseException.class,()->body("{\"x\":\""+"x".repeat(262144)+"\"}").originalBytes());
    }
    @Test void strictLookupEnvelopeReturnsOnlyTheTwoAgreedFields() {
        var parsed=InformationReceiptBody.parse("{\"operation\":\"REPLY\",\"originalBodyBase64\":\"e30=\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("REPLY",parsed.operation());assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8),parsed.originalBytes());
    }
}
