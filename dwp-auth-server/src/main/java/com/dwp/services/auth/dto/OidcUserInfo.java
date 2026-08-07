package com.dwp.services.auth.dto;

public record OidcUserInfo(String subject, String email, String name) {

    public String principal() {
        if (email != null && !email.isBlank()) return email;
        return subject;
    }
}
