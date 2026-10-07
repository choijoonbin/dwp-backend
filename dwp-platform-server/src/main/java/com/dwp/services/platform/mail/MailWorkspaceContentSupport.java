package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;

class MailWorkspaceContentSupport {

    static final long BYTES_PER_MIB = 1024L * 1024L;
    static final Set<String> ALLOWED_TEMPLATE_VARIABLES = Set.of(
            "displayName", "department", "recipientName");
    static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}", Pattern.UNICODE_CASE);
    static final Safelist MAIL_HTML = new Safelist()
            .addTags(
                    "p", "div", "br", "strong", "b", "em", "i", "u", "s",
                    "ul", "ol", "li", "blockquote", "pre", "code", "a",
                    "h1", "h2", "h3", "h4", "h5", "h6")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https", "mailto");

    final MailWorkspaceRepository repository;
    final MailQueryRepository queries;
    final MailService mail;
    final TenantMediaStorage storage;
    final List<MailAttachmentScanner> attachmentScanners;
    final MailConnectorRegistry connectors;

    MailWorkspaceContentSupport(
            MailWorkspaceRepository repository,
            MailQueryRepository queries,
            MailService mail,
            TenantMediaStorage storage,
            List<MailAttachmentScanner> attachmentScanners,
            MailConnectorRegistry connectors) {
        this.repository = repository;
        this.queries = queries;
        this.mail = mail;
        this.storage = storage;
        this.attachmentScanners = List.copyOf(attachmentScanners);
        this.connectors = connectors;
    }

    String emailDomain(String email) {
        String normalized = value(email).toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf('@');
        if (separator < 1 || separator == normalized.length() - 1) {
            throw invalid("A recipient email address is invalid.");
        }
        return normalized.substring(separator + 1);
    }

    void requireAttachmentScanningAvailable(List<UUID> attachmentIds) {
        if (!attachmentIds.isEmpty()) requireAttachmentScanningAvailable();
    }

    void requireAttachmentScanningAvailable() {
        if (attachmentScanners.isEmpty()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Mail attachments are unavailable until a trusted content scanner is configured.");
        }
    }

    String scanAttachment(
            long tenantId,
            long userId,
            UUID attachmentId,
            ValidatedAttachment attachment) {
        List<String> evidence = new ArrayList<>();
        for (MailAttachmentScanner scanner : attachmentScanners) {
            MailAttachmentScanner.ScanResult result;
            try {
                result = scanner.scan(new MailAttachmentScanner.ScanRequest(
                        tenantId, userId, attachmentId, attachment.fileName(),
                        attachment.contentType(), attachment.checksum(), attachment.content()));
            } catch (RuntimeException failure) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "The attachment content scanner is unavailable.",
                        failure);
            }
            if (result == null) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "The attachment content scanner returned no verdict.");
            }
            if (result.verdict() == MailAttachmentScanner.Verdict.REJECTED) {
                throw invalid("The attachment was rejected by the content scanner.");
            }
            if (result.evidence() == null || result.evidence().isBlank()) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "The attachment content scanner returned no evidence.");
            }
            evidence.add(result.evidence());
        }
        String combined = String.join(" | ", evidence);
        if (combined.length() > 320) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The attachment content scanner evidence is too large.");
        }
        return combined;
    }

    void requireAssetAccount(UUID composeAccountId, UUID assetAccountId) {
        if (assetAccountId != null && !assetAccountId.equals(composeAccountId)) {
            throw invalid("The writing asset belongs to a different sending account.");
        }
    }

    void validateAssetScope(
            long tenantId, long userId, AssetScope scope, UUID accountId) {
        if (scope == AssetScope.PERSONAL && accountId != null
                || scope == AssetScope.ACCOUNT && accountId == null
                || scope == AssetScope.ORGANIZATION && accountId != null) {
            throw invalid("The writing asset scope and account do not match.");
        }
        if (accountId != null && !repository.accountAccessible(tenantId, userId, accountId)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The selected account is not available for this writing asset.");
        }
    }

    void requireEditableScope(AssetScope scope) {
        if (scope == AssetScope.ORGANIZATION) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Organization writing assets are managed by an administrator.");
        }
    }

    TemplateRequest sanitized(TemplateRequest request) {
        return new TemplateRequest(
                request.name(), request.subject(),
                request.bodyFormat() == BodyFormat.HTML
                        ? sanitizedHtml(request.body()) : request.body().trim(),
                request.bodyFormat(), request.scope(), request.accountId(), request.version());
    }

    SignatureRequest sanitized(SignatureRequest request) {
        return new SignatureRequest(
                request.name(), request.bodyFormat() == BodyFormat.HTML
                        ? sanitizedHtml(request.body()) : request.body().trim(),
                request.bodyFormat(), request.scope(), request.accountId(),
                request.defaultForNew(), request.defaultForReply(), request.version());
    }

    void validateTemplateVariables(String subject, String body) {
        for (String value : List.of(subject == null ? "" : subject, body == null ? "" : body)) {
            Matcher matcher = TEMPLATE_VARIABLE.matcher(value);
            while (matcher.find()) {
                if (!ALLOWED_TEMPLATE_VARIABLES.contains(matcher.group(1))) {
                    throw invalid("Unsupported template variable: " + matcher.group(1));
                }
            }
        }
    }

    void validateCriteria(long tenantId, long userId, SearchCriteria criteria) {
        if (criteria.accountId() != null
                && !repository.accountAccessible(tenantId, userId, criteria.accountId())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The saved view account is not available.");
        }
        if (criteria.dateFrom() != null && criteria.dateTo() != null
                && criteria.dateFrom().isAfter(criteria.dateTo())) {
            throw invalid("The saved view date range is invalid.");
        }
        if (criteria.scope() != null
                && !Set.of("ALL", "PERSONAL", "SHARED").contains(criteria.scope())) {
            throw invalid("The saved view scope is invalid.");
        }
    }

    void validateFollowUp(FollowUpRequest request) {
        try {
            java.time.ZoneId.of(request.timeZone());
        } catch (RuntimeException failure) {
            throw invalid("The follow-up time zone is invalid.");
        }
        if (!request.expectedReplyAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw invalid("The expected reply time must be in the future.");
        }
    }

    List<Recipient> normalizedRecipients(List<Recipient> recipients) {
        LinkedHashMap<String, Recipient> unique = new LinkedHashMap<>();
        for (Recipient recipient : recipients) {
            String email = recipient.email().trim().toLowerCase(Locale.ROOT);
            String key = recipient.type().name() + ":" + email;
            unique.putIfAbsent(key, new Recipient(
                    recipient.type(), nullable(recipient.name()), email));
        }
        if (unique.isEmpty()) throw invalid("At least one recipient is required.");
        return List.copyOf(unique.values());
    }

    List<UUID> distinctIds(List<UUID> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<UUID> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID value : values) if (value != null && seen.add(value)) result.add(value);
        return List.copyOf(result);
    }

    OffsetDateTime normalizedSchedule(OffsetDateTime value, String timeZone) {
        if (value == null) return null;
        if (timeZone == null || timeZone.isBlank()) {
            throw invalid("A time zone is required for scheduled delivery.");
        }
        try {
            java.time.ZoneId.of(timeZone);
        } catch (RuntimeException failure) {
            throw invalid("The scheduled delivery time zone is invalid.");
        }
        if (!value.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30))) {
            throw invalid("Scheduled delivery must be in the future.");
        }
        if (value.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusYears(1))) {
            throw invalid("Scheduled delivery cannot be more than one year in the future.");
        }
        return value;
    }

    String sanitizedHtml(String html) {
        String sanitized = Jsoup.clean(
                html.trim(), "", MAIL_HTML,
                new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
        if (Jsoup.parseBodyFragment(sanitized).text().isBlank()) {
            throw invalid("The HTML message has no safe content.");
        }
        return sanitized;
    }

    ValidatedAttachment validateAttachment(long tenantId, MultipartFile file) {
        long maximumBytes = repository.maximumAttachmentMb(tenantId) * 1024L * 1024L;
        if (file == null || file.isEmpty() || file.getSize() < 1 || file.getSize() > maximumBytes) {
            throw invalid("The attachment exceeds the organization size policy.");
        }
        try {
            byte[] content = file.getBytes();
            DetectedMedia media = detect(content, file.getContentType(), file.getOriginalFilename());
            return new ValidatedAttachment(safeFileName(file.getOriginalFilename()),
                    media.contentType(), media.extension(), content, sha256(content));
        } catch (IOException failure) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The attachment could not be read.", failure);
        }
    }

    DetectedMedia detect(byte[] content, String declared, String name) {
        if (starts(content, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return exactDeclared(declared, "image/png", "png");
        }
        if (starts(content, new int[]{0xff, 0xd8, 0xff})) {
            return exactDeclared(declared, "image/jpeg", "jpg");
        }
        if (starts(content, new int[]{'%', 'P', 'D', 'F', '-'})) {
            return exactDeclared(declared, "application/pdf", "pdf");
        }
        if (starts(content, new int[]{'P', 'K', 0x03, 0x04})) {
            String lower = safeFileName(name).toLowerCase(Locale.ROOT);
            if (lower.endsWith(".docx")) return declaredOneOf(declared,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
            if (lower.endsWith(".xlsx")) return declaredOneOf(declared,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
            if (lower.endsWith(".pptx")) return declaredOneOf(declared,
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
            return declaredOneOf(declared, "application/zip", "zip");
        }
        if ("text/plain".equals(declared)) {
            try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content));
                return new DetectedMedia("text/plain", "txt");
            } catch (CharacterCodingException failure) {
                throw invalid("The text attachment is not valid UTF-8.");
            }
        }
        throw invalid("This attachment type is not allowed by the mail content policy.");
    }

    DetectedMedia exactDeclared(String declared, String expected, String extension) {
        if (!expected.equals(declared)) throw invalid("The attachment content type does not match its bytes.");
        return new DetectedMedia(expected, extension);
    }

    DetectedMedia declaredOneOf(String declared, String expected, String extension) {
        if (declared == null || !(declared.equals(expected)
                || declared.equals("application/octet-stream")
                || declared.equals("application/zip"))) {
            throw invalid("The attachment content type does not match its file format.");
        }
        return new DetectedMedia(expected, extension);
    }

    boolean starts(byte[] content, int[] signature) {
        if (content.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if ((content[index] & 0xff) != signature[index]) return false;
        }
        return true;
    }

    String safeFileName(String value) {
        if (value == null) throw invalid("The attachment file name is required.");
        String candidate = value.replace('\\', '/');
        candidate = candidate.substring(candidate.lastIndexOf('/') + 1).trim();
        if (candidate.isBlank() || candidate.length() > 255
                || candidate.chars().anyMatch(Character::isISOControl)) {
            throw invalid("The attachment file name is invalid.");
        }
        return candidate;
    }

    String jsonRecipients(List<Recipient> recipients) {
        return recipients.stream().map(recipient -> recipient.type() + ":"
                + recipient.email() + ":" + value(recipient.name())).reduce("", (a, b) -> a + "|" + b);
    }

    String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    void requireVersion(Long version) {
        if (version == null || version < 0) throw invalid("A current version is required.");
    }

    String correlation(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String normalized = value.trim();
        if (normalized.length() > 160) throw invalid("The correlation id is too long.");
        return normalized;
    }

    String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    String value(String value) {
        return value == null ? "" : value.trim();
    }

    BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    record ValidatedAttachment(
            String fileName, String contentType, String extension,
            byte[] content, String checksum) {
    }

    record DetectedMedia(String contentType, String extension) {
    }

}
