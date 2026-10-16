package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferDtos.FileTransferCommand;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlledTransferJsonBoundaryTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void commandRejectsUnknownRootPropertyWithLenientGlobalMapper() {
        assertThatThrownBy(() -> json.readValue(validJson()
                        .replace("\"expiresAt\"", "\"clientTenantId\":99,\"expiresAt\""),
                FileTransferCommand.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown FileTransferCommand property");
    }

    @Test
    void commandRejectsUnknownNestedEvidencePropertyWithLenientGlobalMapper() {
        assertThatThrownBy(() -> json.readValue(validJson()
                        .replace("\"verdict\"", "\"signedUrl\":\"secret\",\"verdict\""),
                FileTransferCommand.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown malwareScanSnapshot property");
    }

    private String validJson() {
        return """
                {
                  "ownerModule":"SYS",
                  "transferKind":"INGEST",
                  "objectRef":"obj_1234567890abcdef",
                  "objectVersion":"v1",
                  "objectDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "byteSize":10,
                  "mediaType":"application/json",
                  "malwareScanSnapshot":{
                    "scannerKey":"scanner","scannerVersion":"1","verdict":"CLEAN",
                    "scannedAt":"2026-10-16T00:00:00Z",
                    "objectDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "byteSize":10
                  },
                  "toctouBinding":{
                    "uploadSessionId":"11111111-1111-4111-8111-111111111111",
                    "storageETag":"etag","boundObjectVersion":"v1",
                    "boundDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "boundByteSize":10,
                    "snapshotDigest":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                  },
                  "classification":"CONFIDENTIAL",
                  "purposeCode":"HRIS_TRANSFER",
                  "expiresAt":"2026-10-17T00:00:00Z"
                }
                """;
    }
}
