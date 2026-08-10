package com.dwp.services.platform.auditcontrol;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class AuditIntegrityService {

    private final AuditControlRepository repository;
    private final byte[] secret;
    private final boolean configured;

    public AuditIntegrityService(
            AuditControlRepository repository,
            @Value("${dwp.platform.audit.integrity-secret:}") String integritySecret) {
        this.repository = repository;
        this.configured = integritySecret != null && !integritySecret.isBlank();
        this.secret = (configured ? integritySecret : "dwp-audit-integrity-unconfigured")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public void checkpoint(Long tenantId, LocalDate date) {
        Map<String, Object> source = repository.integritySource(tenantId, date);
        long count = ((Number) source.get("record_count")).longValue();
        Instant first = timestamp(source.get("first_event_at"));
        Instant last = timestamp(source.get("last_event_at"));
        String root = sha256(String.valueOf(source.get("hashes")));
        String previous = repository.previousCheckpointHash(tenantId, date);
        String checkpoint = sha256(String.join("|",
                String.valueOf(tenantId), date.toString(), String.valueOf(count), root,
                previous == null ? "GENESIS" : previous));
        String signature = hmac(checkpoint);
        String status = configured ? "VERIFIED" : "UNAVAILABLE";
        repository.saveCheckpoint(
                tenantId, date, count, first, last, root, previous, checkpoint, signature, status);
    }

    @Transactional(readOnly = true)
    public List<AuditControlDtos.IntegrityCheckpoint> list(Long tenantId) {
        return repository.integrity(tenantId);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Audit integrity signature cannot be generated", exception);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Instant timestamp(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime dateTime) return dateTime.toInstant();
        return null;
    }
}
