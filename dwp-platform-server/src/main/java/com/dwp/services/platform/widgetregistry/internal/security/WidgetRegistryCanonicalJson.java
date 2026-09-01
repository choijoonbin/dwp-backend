package com.dwp.services.platform.widgetregistry.internal.security;

import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;

/** Canonical JSON encoder shared by request and manifest validation. */
final class WidgetRegistryCanonicalJson {

    private WidgetRegistryCanonicalJson() {
    }

    static byte[] encode(byte[] rawJson) throws WidgetRegistryBindingException {
        try {
            return new JsonCanonicalizer(rawJson).getEncodedUTF8();
        } catch (IOException | RuntimeException exception) {
            throw new WidgetRegistryBindingException(
                    WidgetRegistryIngressFailure.REQUEST_BINDING_INVALID, exception);
        }
    }
}
