package com.dwp.services.platform.workplace.workplaceassistant;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.RedactionState;

@Component
public final class WorkplaceAssistantRedactor {
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?82[- .]?)?(?:0?1[016789]|0?2|0?[3-6][1-5])[- .]?\\d{3,4}[- .]?\\d{4}(?!\\d)");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|secret|access[_ -]?token|refresh[_ -]?token|api[_ -]?key)"
                    + "\\s*[:=]\\s*[^\\s,;]{4,}");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}");

    public RedactedText redact(String value) {
        String redacted = value;
        redacted = EMAIL.matcher(redacted).replaceAll("[email redacted]");
        redacted = PHONE.matcher(redacted).replaceAll("[phone redacted]");
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1=[secret redacted]");
        redacted = BEARER.matcher(redacted).replaceAll("Bearer [token redacted]");
        RedactionState state = redacted.equals(value)
                ? RedactionState.NOT_REQUIRED : RedactionState.APPLIED;
        return new RedactedText(redacted, state);
    }

    public record RedactedText(String value, RedactionState state) { }
}
