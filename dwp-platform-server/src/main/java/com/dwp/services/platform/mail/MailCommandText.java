package com.dwp.services.platform.mail;

/** Canonicalizes human-entered values before command persistence. */
final class MailCommandText {

    private MailCommandText() { }

    static String preview(String body) {
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1200 ? normalized : normalized.substring(0, 1197) + "...";
    }

    static String recipientName(MailDtos.ComposeRequest request) {
        return recipientName(request.toName(), request.toEmail());
    }

    static String recipientName(String name, String email) {
        return name != null && !name.isBlank() ? name.trim() : email.trim();
    }

    static String value(String input) {
        return input == null ? "" : input.trim();
    }
}
