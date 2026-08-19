package com.dwp.services.messaging.attachment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class LocalAttachmentScanner implements AttachmentScanner {

    private static final byte[] EICAR = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE"
            .getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> OOXML_EXTENSIONS = Set.of("docx", "xlsx", "pptx");

    @Override
    public ScanResult scan(ScanRequest request, byte[] content) {
        String detected = detect(content, request.extension());
        if (contains(content, EICAR)) {
            return ScanResult.rejected(detected, "MALWARE_SIGNATURE");
        }
        if (isExecutable(content)) {
            return ScanResult.rejected(detected, "EXECUTABLE_CONTENT");
        }
        if ("application/zip".equals(detected)) {
            if (!OOXML_EXTENSIONS.contains(request.extension())) {
                return ScanResult.rejected(detected, "ARCHIVE_CONTENT");
            }
            String reason = validateOfficeArchive(request.extension(), content);
            if (reason != null) return ScanResult.rejected(detected, reason);
            detected = request.declaredContentType();
        }
        if (!compatible(request, detected)) {
            return ScanResult.rejected(detected, "CONTENT_TYPE_MISMATCH");
        }
        return ScanResult.clean(detected);
    }

    private String validateOfficeArchive(String extension, byte[] content) {
        String requiredPrefix = switch (extension) {
            case "docx" -> "word/";
            case "xlsx" -> "xl/";
            case "pptx" -> "ppt/";
            default -> "";
        };
        boolean hasContentTypes = false;
        boolean hasOfficePayload = false;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 2_000) return "ARCHIVE_ENTRY_LIMIT";
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) return "ARCHIVE_PATH_TRAVERSAL";
                hasContentTypes |= "[Content_Types].xml".equals(name);
                hasOfficePayload |= name.startsWith(requiredPrefix);
            }
        } catch (IOException exception) {
            return "MALFORMED_OFFICE_DOCUMENT";
        }
        return hasContentTypes && hasOfficePayload ? null : "MALFORMED_OFFICE_DOCUMENT";
    }

    private boolean compatible(ScanRequest request, String detected) {
        if (request.declaredContentType().equals(detected)) return true;
        return "csv".equals(request.extension()) && "text/plain".equals(detected);
    }

    private String detect(byte[] content, String extension) {
        if (starts(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) return "application/pdf";
        if (starts(content, new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) return "image/png";
        if (starts(content, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "image/jpeg";
        if (starts(content, "GIF8".getBytes(StandardCharsets.US_ASCII))) return "image/gif";
        if (starts(content, new byte[] {'P', 'K', 3, 4})) return "application/zip";
        if ("txt".equals(extension) || "csv".equals(extension)) return "text/plain";
        return "application/octet-stream";
    }

    private boolean isExecutable(byte[] content) {
        if (starts(content, new byte[] {'M', 'Z'})) return true;
        if (starts(content, new byte[] {0x7f, 'E', 'L', 'F'})) return true;
        String prefix = new String(content, 0, Math.min(content.length, 128), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        return prefix.startsWith("#!") || prefix.contains("<script") || prefix.contains("powershell");
    }

    private boolean starts(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) return false;
        }
        return true;
    }

    private boolean contains(byte[] content, byte[] needle) {
        outer: for (int index = 0; index <= content.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (content[index + offset] != needle[offset]) continue outer;
            }
            return true;
        }
        return false;
    }
}
