package com.dwp.services.approval.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ApprovalAttachmentIntegrity {
    private ApprovalAttachmentIntegrity() { }
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static void require(byte[] bytes, long size, String sha) {
        if (size < 1 || bytes.length != size || sha == null || !sha.matches("[a-f0-9]{64}") || !sha(bytes).equals(sha))
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "Attachment content integrity changed.");
    }
    public static BaseException unavailable(String reason) { return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, reason); }
}
