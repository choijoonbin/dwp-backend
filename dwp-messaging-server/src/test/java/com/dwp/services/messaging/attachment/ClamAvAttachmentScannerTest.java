package com.dwp.services.messaging.attachment;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ClamAvAttachmentScannerTest {

    @Test
    void reportsReadyOnlyAfterAnExactPingPongRoundTrip() throws Exception {
        try (FakeClamAvServer server = FakeClamAvServer.reply("PONG\0")) {
            ClamAvAttachmentScanner scanner = scanner(server.port());

            assertThat(scanner.health()).isEqualTo(AttachmentScanner.ProviderHealth.ready());
            assertThat(server.request()).isEqualTo("zPING\0".getBytes(StandardCharsets.US_ASCII));
        }

        try (FakeClamAvServer server = FakeClamAvServer.reply("UNKNOWN COMMAND\0")) {
            assertThat(scanner(server.port()).health()).isEqualTo(
                    AttachmentScanner.ProviderHealth.unavailable("CLAMAV_UNEXPECTED_REPLY"));
        }
    }

    @Test
    void streamsContentAndAcceptsOnlyAClamAvAndLocalPolicyCleanVerdict() throws Exception {
        byte[] content = "reviewed notes".getBytes(StandardCharsets.UTF_8);
        try (FakeClamAvServer server = FakeClamAvServer.reply("stream: OK\0")) {
            AttachmentScanner.ScanResult result = scanner(server.port()).scan(
                    new AttachmentScanner.ScanRequest("notes.txt", "txt", "text/plain"), content);

            assertThat(result.clean()).isTrue();
            assertThat(result.detectedContentType()).isEqualTo("text/plain");
            assertThat(readInstreamContent(server.request())).isEqualTo(content);
        }
    }

    @Test
    void rejectsMalwareAndUnexpectedScannerVerdicts() throws Exception {
        try (FakeClamAvServer server = FakeClamAvServer.reply(
                "stream: Eicar-Test-Signature FOUND\0")) {
            AttachmentScanner.ScanResult result = scanner(server.port()).scan(
                    new AttachmentScanner.ScanRequest("notes.txt", "txt", "text/plain"),
                    "content".getBytes(StandardCharsets.UTF_8));

            assertThat(result.clean()).isFalse();
            assertThat(result.reason()).isEqualTo("MALWARE_DETECTED");
        }

        try (FakeClamAvServer server = FakeClamAvServer.reply("stream: ERROR\0")) {
            AttachmentScanner.ScanResult result = scanner(server.port()).scan(
                    new AttachmentScanner.ScanRequest("notes.txt", "txt", "text/plain"),
                    "content".getBytes(StandardCharsets.UTF_8));

            assertThat(result.clean()).isFalse();
            assertThat(result.reason()).isEqualTo("SCANNER_ERROR");
        }
    }

    @Test
    void failsClosedWhenTheDaemonIsUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        ClamAvAttachmentScanner scanner = scanner(closedPort);

        assertThat(scanner.health()).isEqualTo(
                AttachmentScanner.ProviderHealth.unavailable("CLAMAV_UNAVAILABLE"));
        AttachmentScanner.ScanResult result = scanner.scan(
                new AttachmentScanner.ScanRequest("notes.txt", "txt", "text/plain"),
                "content".getBytes(StandardCharsets.UTF_8));
        assertThat(result.clean()).isFalse();
        assertThat(result.reason()).isEqualTo("SCANNER_UNAVAILABLE");
    }

    private ClamAvAttachmentScanner scanner(int port) {
        return new ClamAvAttachmentScanner(new AttachmentProperties(
                "local", Path.of(".dwp-data/messaging-attachments"), "clamav",
                Duration.ofMinutes(10), Duration.ofMinutes(1), 100, 2,
                InetAddress.getLoopbackAddress().getHostAddress(), port, Duration.ofSeconds(1)));
    }

    private byte[] readInstreamContent(byte[] request) throws Exception {
        byte[] command = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
        assertThat(request).startsWith(command);
        try (DataInputStream input = new DataInputStream(
                new java.io.ByteArrayInputStream(request, command.length, request.length - command.length));
             ByteArrayOutputStream content = new ByteArrayOutputStream()) {
            int length;
            while ((length = input.readInt()) > 0) content.write(input.readNBytes(length));
            assertThat(length).isZero();
            return content.toByteArray();
        }
    }

    private static final class FakeClamAvServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService executor;
        private final Future<?> exchange;
        private volatile byte[] request;

        private FakeClamAvServer(byte[] response) throws Exception {
            serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            executor = Executors.newSingleThreadExecutor();
            exchange = executor.submit(() -> {
                try (Socket socket = serverSocket.accept()) {
                    ByteArrayOutputStream captured = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8_192];
                    socket.setSoTimeout(1_000);
                    int read;
                    while ((read = socket.getInputStream().read(buffer)) > 0) {
                        captured.write(buffer, 0, read);
                        if (completeRequest(captured.toByteArray())) break;
                    }
                    request = captured.toByteArray();
                    socket.getOutputStream().write(response);
                    socket.getOutputStream().flush();
                    socket.shutdownOutput();
                }
                return null;
            });
        }

        static FakeClamAvServer reply(String reply) throws Exception {
            return new FakeClamAvServer(reply.getBytes(StandardCharsets.US_ASCII));
        }

        private static boolean completeRequest(byte[] request) {
            byte[] ping = "zPING\0".getBytes(StandardCharsets.US_ASCII);
            if (java.util.Arrays.equals(request, ping)) return true;
            byte[] command = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
            if (request.length < command.length + Integer.BYTES) return false;
            int offset = command.length;
            while (offset + Integer.BYTES <= request.length) {
                int length = java.nio.ByteBuffer.wrap(request, offset, Integer.BYTES).getInt();
                offset += Integer.BYTES;
                if (length == 0) return offset == request.length;
                if (length < 0 || offset + length > request.length) return false;
                offset += length;
            }
            return false;
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        byte[] request() throws Exception {
            exchange.get(2, TimeUnit.SECONDS);
            return request;
        }

        @Override
        public void close() {
            try {
                serverSocket.close();
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Fake ClamAV server did not stop.");
                }
            } catch (java.io.IOException exception) {
                throw new AssertionError("Fake ClamAV server could not close.", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while stopping fake ClamAV server.", exception);
            }
        }
    }
}
