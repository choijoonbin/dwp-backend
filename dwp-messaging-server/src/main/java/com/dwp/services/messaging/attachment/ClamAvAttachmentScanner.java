package com.dwp.services.messaging.attachment;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class ClamAvAttachmentScanner implements AttachmentScanner {

    private final AttachmentProperties properties;

    ClamAvAttachmentScanner(AttachmentProperties properties) {
        this.properties = properties;
    }

    @Override
    public ScanResult scan(ScanRequest request, byte[] content) {
        try (Socket socket = new Socket()) {
            int timeout = Math.toIntExact(properties.clamavTimeout().toMillis());
            socket.connect(new InetSocketAddress(
                    properties.clamavHost(), properties.clamavPort()), timeout);
            socket.setSoTimeout(timeout);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < content.length; offset += 8192) {
                int length = Math.min(8192, content.length - offset);
                output.writeInt(length);
                output.write(content, offset, length);
            }
            output.writeInt(0);
            output.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(response);
            String verdict = response.toString(StandardCharsets.US_ASCII).trim();
            if (verdict.endsWith("OK")) {
                return new LocalAttachmentScanner().scan(request, content);
            }
            if (verdict.contains("FOUND")) {
                return ScanResult.rejected(request.declaredContentType(), "MALWARE_DETECTED");
            }
            return ScanResult.rejected(request.declaredContentType(), "SCANNER_ERROR");
        } catch (IOException | ArithmeticException exception) {
            return ScanResult.rejected(request.declaredContentType(), "SCANNER_UNAVAILABLE");
        }
    }
}
