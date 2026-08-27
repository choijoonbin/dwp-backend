package com.dwp.core.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Canonical length-prefixed binary envelope encoded as unpadded Base64URL. */
public final class EnvelopeCiphertextCodec {

    public static final String PREFIX = "dwp2.";
    private static final byte[] MAGIC = {'D', 'W', 'P', '2'};
    private static final int MAX_ENCODED_CHARACTERS = 24 * 1024 * 1024;

    public String encode(EnvelopeCiphertext envelope) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(MAGIC);
                writeString(output, envelope.contentAlgorithm());
                writeString(output, envelope.aadProfile());
                KeySlot slot = envelope.keySlot();
                writeString(output, slot.provider());
                writeString(output, slot.immutableKeyId());
                writeNullableString(output, slot.keyVersion());
                writeString(output, slot.wrapAlgorithm());
                writeBytes(output, slot.wrappedDek());
                writeBytes(output, envelope.nonce());
                writeBytes(output, envelope.ciphertext());
                writeBytes(output, envelope.aadSha256());
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Encryption envelope encoding failed.", exception);
        }
    }

    public EnvelopeCiphertext decode(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)
                || encoded.length() > MAX_ENCODED_CHARACTERS) {
            throw new IllegalArgumentException("Encrypted value is not a supported DWP envelope.");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(encoded.substring(PREFIX.length()));
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
            if (!canonical.equals(encoded.substring(PREFIX.length()))) {
                throw new IllegalArgumentException("Encryption envelope Base64URL is not canonical.");
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                for (byte expected : MAGIC) {
                    if (input.readByte() != expected) {
                        throw new IllegalArgumentException("Encryption envelope magic is invalid.");
                    }
                }
                String contentAlgorithm = readString(input, 64);
                String aadProfile = readString(input, 64);
                String provider = readString(input, 64);
                String immutableKeyId = readString(input, 1024);
                String keyVersion = readNullableString(input, 128);
                String wrapAlgorithm = readString(input, 64);
                byte[] wrappedDek = readBytes(input, 16_384);
                byte[] nonce = readBytes(input, 12);
                byte[] ciphertext = readBytes(input, 16 * 1024 * 1024);
                byte[] aadSha256 = readBytes(input, 32);
                if (input.available() != 0) {
                    throw new IllegalArgumentException("Encryption envelope has trailing data.");
                }
                return new EnvelopeCiphertext(
                        EnvelopeCiphertext.FORMAT_VERSION,
                        contentAlgorithm,
                        aadProfile,
                        new KeySlot(
                                provider,
                                immutableKeyId,
                                keyVersion,
                                wrapAlgorithm,
                                wrappedDek),
                        nonce,
                        ciphertext,
                        aadSha256);
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Encrypted value is not a valid DWP envelope.", exception);
        }
    }

    public boolean isEnvelope(String encoded) {
        return encoded != null && encoded.startsWith(PREFIX);
    }

    private void writeString(DataOutputStream output, String value) throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private void writeNullableString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
        } else {
            writeString(output, value);
        }
    }

    private void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private String readString(DataInputStream input, int maximumBytes) throws IOException {
        return new String(readBytes(input, maximumBytes), StandardCharsets.UTF_8);
    }

    private String readNullableString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length == -1) return null;
        return new String(readExact(input, length, maximumBytes), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        return readExact(input, length, maximumBytes);
    }

    private byte[] readExact(DataInputStream input, int length, int maximumBytes) throws IOException {
        if (length < 0 || length > maximumBytes) {
            throw new IllegalArgumentException("Encryption envelope field length is invalid.");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) throw new EOFException("Encryption envelope is truncated.");
        return value;
    }
}
