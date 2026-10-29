package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrustedHttpMailAttachmentScannerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void acceptsOnlyBoundedCleanReceiptAndSendsChecksumMetadata() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(
                "{\"verdict\":\"CLEAN\",\"evidence\":\"engine:rule-42\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        doReturn(response).when(client).send(any(), any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TrustedHttpMailAttachmentScanner scanner = new TrustedHttpMailAttachmentScanner(
                new ObjectMapper(), new MailMetrics(registry),
                URI.create("https://scanner.example/"), "secret-token",
                Duration.ofSeconds(1), 1024, 4096, client);
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        MailAttachmentScanner.ScanRequest request = new MailAttachmentScanner.ScanRequest(
                7, 91, UUID.randomUUID(), "private-file-name.txt", "text/plain",
                "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
                content);

        MailAttachmentScanner.ScanResult result = scanner.scan(request);

        assertThat(result.verdict()).isEqualTo(MailAttachmentScanner.Verdict.CLEAN);
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(sent.capture(), any());
        assertThat(sent.getValue().uri()).isEqualTo(URI.create("https://scanner.example/v1/scan"));
        assertThat(sent.getValue().headers().firstValue("X-Content-SHA256"))
                .contains(request.checksumSha256());
        assertThat(sent.getValue().headers().map().toString())
                .doesNotContain("private-file-name.txt");
        assertThat(registry.get("dwp.mail.attachment.scan")
                .tag("outcome", "clean").counter().count()).isOne();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void malformedOrOversizedOwnerResponseFailsClosed() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(
                "{\"verdict\":\"MAYBE\",\"evidence\":\"token must not pass\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        doReturn(response).when(client).send(any(), any());
        TrustedHttpMailAttachmentScanner scanner = new TrustedHttpMailAttachmentScanner(
                new ObjectMapper(), new MailMetrics(new SimpleMeterRegistry()),
                URI.create("https://scanner.example/"), "secret-token",
                Duration.ofSeconds(1), 1024, 4096, client);
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);

        MailAttachmentScanner.ScanResult result = scanner.scan(
                new MailAttachmentScanner.ScanRequest(
                        7, 91, UUID.randomUUID(), "file.txt", "text/plain",
                        "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
                        content));

        assertThat(result.verdict()).isEqualTo(MailAttachmentScanner.Verdict.REJECTED);
        assertThat(result.evidence()).isEqualTo("SCANNER_INVALID_RESPONSE");
    }
}
