package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;

import java.util.Objects;

/** Reuses PDX_004/005/006 while enforcing the narrower SYS-006 numeric wire subset. */
public final class Sys006TypedArtifactContract {

    public static final long MAX_JSON_SAFE_INTEGER = 9_007_199_254_740_991L;
    public static final long MAX_ITEM_SEQUENCE = 1_000_000_000L;
    public static final int MAX_ATTEMPTS = 20;

    private Sys006TypedArtifactContract() {}

    public static AutomationHandlerManifestV1 requireInteroperable(
            AutomationHandlerManifestV1 manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        if (manifest.maxAttempts() < 1 || manifest.maxAttempts() > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("manifest maxAttempts is outside SYS-006 bounds");
        }
        return manifest;
    }

    public static AutomationInvocationV1 requireInteroperable(
            AutomationInvocationV1 invocation) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        requireJsonSafePositive(invocation.automationVersion(), "automationVersion");
        if (invocation.expectedItemCount() < 0
                || invocation.expectedItemCount() > MAX_ITEM_SEQUENCE) {
            throw new IllegalArgumentException(
                    "expectedItemCount is outside SYS-006 bounds");
        }
        return invocation;
    }

    public static AutomationRunItemReceiptV1 requireInteroperable(
            AutomationRunItemReceiptV1 receipt) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        requireJsonSafePositive(receipt.runRevision(), "runRevision");
        if (receipt.itemSequence() < 1L
                || receipt.itemSequence() > MAX_ITEM_SEQUENCE) {
            throw new IllegalArgumentException("itemSequence is outside SYS-006 bounds");
        }
        if (receipt.attemptNumber() < 1 || receipt.attemptNumber() > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("attemptNumber is outside SYS-006 bounds");
        }
        return receipt;
    }

    private static void requireJsonSafePositive(long value, String name) {
        if (value < 1L || value > MAX_JSON_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    name + " must be a positive JSON-safe integer");
        }
    }
}
