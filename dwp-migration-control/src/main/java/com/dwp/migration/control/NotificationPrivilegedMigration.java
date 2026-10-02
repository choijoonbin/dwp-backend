package com.dwp.migration.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

enum NotificationPrivilegedMigration {
    ROLE_FOUNDATION(
            "2",
            "V2__enforce_notification_row_security.sql",
            "8943baa1fe0fa4a30d26c7bd9fedd3144d13cf35ae4f3c04d967d6194fefa45c",
            Set.of("dwp_notification_api", "dwp_notification_worker")),
    AUDIT_RELAY(
            "22",
            "V22__isolate_notification_audit_relay.sql",
            "c8de69b4b1b55e84781b567867b7b2beb617144605ef3490dc2c769f4b2688e4",
            Set.of("dwp_notification_audit_relay"));

    private static final Pattern CREATE_ROLE = Pattern.compile(
            "(?i)\\bCREATE\\s+ROLE\\s+([a-z_][a-z0-9_]*)");
    private static final Pattern ALTER_ROLE = Pattern.compile(
            "(?i)\\bALTER\\s+ROLE\\s+([a-z_][a-z0-9_]*)");
    private static final Pattern FORBIDDEN_AUTHORITY = Pattern.compile(
            "(?is)\\b(?:RESET\\s+ROLE|SET\\s+(?:LOCAL\\s+)?ROLE"
                    + "|SET\\s+SESSION\\s+AUTHORIZATION|ALTER\\s+SYSTEM"
                    + "|CREATE\\s+DATABASE|DROP\\s+DATABASE"
                    + "|COPY\\b[^;]*\\bPROGRAM|CREATE\\s+EVENT\\s+TRIGGER"
                    + "|CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TRUSTED\\s+)?LANGUAGE"
                    + "|CREATE\\s+EXTENSION|ALTER\\s+EXTENSION|DROP\\s+EXTENSION"
                    + "|CREATE\\s+FOREIGN\\s+DATA\\s+WRAPPER"
                    + "|CREATE\\s+SUBSCRIPTION|CREATE\\s+PUBLICATION"
                    + "|SECURITY\\s+DEFINER)\\b");

    private final String version;
    private final String fileName;
    private final String sha256;
    private final Set<String> roleTargets;

    NotificationPrivilegedMigration(
            String version,
            String fileName,
            String sha256,
            Set<String> roleTargets) {
        this.version = version;
        this.fileName = fileName;
        this.sha256 = sha256;
        this.roleTargets = Set.copyOf(roleTargets);
    }

    String version() {
        return version;
    }

    String fileName() {
        return fileName;
    }

    Set<String> roleTargets() {
        return roleTargets;
    }

    void requireAttestedSource(ControlEnvironment environment) {
        if (!"notification".equals(environment.plan().service())) {
            throw new IllegalStateException(
                    "Privileged notification migration used outside notification Control");
        }
        byte[] bytes = sourceBytes();
        String actual = HexFormat.of().formatHex(sha256().digest(bytes));
        if (!sha256.equals(actual)) {
            throw new IllegalStateException(
                    "Privileged notification migration source digest differs: " + fileName);
        }
        String source = new String(bytes, StandardCharsets.UTF_8);
        if (FORBIDDEN_AUTHORITY.matcher(source.toUpperCase(Locale.ROOT)).find()) {
            throw new IllegalStateException(
                    "Privileged notification migration contains forbidden authority: "
                            + fileName);
        }
        if (!targets(CREATE_ROLE, source).equals(roleTargets)
                || !targets(ALTER_ROLE, source).equals(roleTargets)) {
            throw new IllegalStateException(
                    "Privileged notification migration role targets differ: " + fileName);
        }
    }

    private byte[] sourceBytes() {
        String resource = "db/migration/" + fileName;
        try (var input = NotificationPrivilegedMigration.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Privileged notification migration is missing: " + resource);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Privileged notification migration cannot be read: " + resource,
                    exception);
        }
    }

    private static Set<String> targets(Pattern pattern, String source) {
        Set<String> targets = new HashSet<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            if (!targets.add(matcher.group(1))) {
                throw new IllegalStateException(
                        "Privileged notification migration repeats a role target");
            }
        }
        return Set.copyOf(targets);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
