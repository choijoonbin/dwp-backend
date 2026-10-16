package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferDtos.*;

public final class ControlledTransferAuthorityPorts {

    private ControlledTransferAuthorityPorts() {
    }

    public interface TrustedObjectMetadataAuthority {
        TrustedObjectMetadata resolve(ObjectMetadataRequest request);
    }

    public interface SignedMalwareScanAuthority {
        VerifiedCleanScan verifySignedCleanSnapshot(SignedScanRequest request);
    }

    public interface EgressPolicyAuthority {
        VerifiedEgressPolicy authorize(EgressPolicyRequest request);
    }

    public record ObjectMetadataRequest(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String objectRef,
            String requestedObjectVersion,
            UUID uploadSessionId,
            String storageETag) {
    }

    public record TrustedObjectMetadata(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String objectRef,
            UUID uploadSessionId,
            String objectVersion,
            String objectDigest,
            long byteSize,
            String mediaType,
            String storageETag,
            String metadataAuthority,
            String metadataSnapshotDigest,
            String authorityDigest,
            OffsetDateTime freshUntil) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown trusted object metadata property: " + name);
        }
    }

    public record SignedScanRequest(
            long tenantId,
            String scannerKey,
            String scannerVersion,
            OffsetDateTime scannedAt,
            String objectDigest,
            long byteSize) {
    }

    public record VerifiedCleanScan(
            long tenantId,
            String scannerKey,
            String scannerVersion,
            OffsetDateTime scannedAt,
            String objectDigest,
            long byteSize,
            String authorityDigest,
            OffsetDateTime freshUntil) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown verified scan property: " + name);
        }
    }

    public record EgressPolicyRequest(
            long tenantId,
            UUID subjectPrincipalPublicId,
            UUID recipientPolicyRef,
            long egressPolicyRevision,
            TransferKind transferKind,
            Classification classification,
            String purposeCode,
            String objectRef,
            String objectVersion,
            String objectDigest,
            long byteSize,
            String mediaType) {
    }

    public record VerifiedEgressPolicy(
            boolean allowed,
            long tenantId,
            UUID subjectPrincipalPublicId,
            UUID recipientPolicyRef,
            long egressPolicyRevision,
            TransferKind transferKind,
            Classification classification,
            String purposeCode,
            String objectRef,
            String objectVersion,
            String objectDigest,
            long byteSize,
            String mediaType,
            String authorityDigest,
            OffsetDateTime freshUntil) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown verified egress policy property: " + name);
        }
    }

    static final class FailClosedObjectMetadataAuthority
            implements TrustedObjectMetadataAuthority {
        @Override
        public TrustedObjectMetadata resolve(ObjectMetadataRequest request) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted object metadata authority is not configured");
        }
    }

    static final class FailClosedMalwareScanAuthority
            implements SignedMalwareScanAuthority {
        @Override
        public VerifiedCleanScan verifySignedCleanSnapshot(SignedScanRequest request) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Signed malware scan authority is not configured");
        }
    }

    static final class FailClosedEgressPolicyAuthority
            implements EgressPolicyAuthority {
        @Override
        public VerifiedEgressPolicy authorize(EgressPolicyRequest request) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Egress policy authority is not configured");
        }
    }
}
