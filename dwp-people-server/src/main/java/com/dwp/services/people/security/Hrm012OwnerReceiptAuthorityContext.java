package com.dwp.services.people.security;

import com.dwp.core.security.Hrm012OwnerReceiptAuthorityVerifier;

/** Marker evidence only; it deliberately carries no command or data authority. */
public final class Hrm012OwnerReceiptAuthorityContext {

    private static final ThreadLocal<Hrm012OwnerReceiptAuthorityVerifier.Evidence> CURRENT =
            new ThreadLocal<>();

    private Hrm012OwnerReceiptAuthorityContext() {
    }

    static void set(Hrm012OwnerReceiptAuthorityVerifier.Evidence evidence) {
        CURRENT.set(evidence);
    }

    public static Hrm012OwnerReceiptAuthorityVerifier.Evidence require() {
        Hrm012OwnerReceiptAuthorityVerifier.Evidence value = CURRENT.get();
        if (value == null) throw new IllegalStateException(
                "HRM-012 owner-receipt boundary evidence is unavailable.");
        return value;
    }

    static void clear() {
        CURRENT.remove();
    }
}
