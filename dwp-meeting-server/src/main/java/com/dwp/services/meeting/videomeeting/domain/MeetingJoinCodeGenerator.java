package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class MeetingJoinCodeGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Pattern VALID = Pattern.compile("^[A-HJ-NP-Z2-9]{10,16}$");

    private final SecureRandom random;
    private final int length;

    @Autowired
    public MeetingJoinCodeGenerator(MeetingMediaProperties properties) {
        this(new SecureRandom(), properties.getJoinCodeLength());
    }

    MeetingJoinCodeGenerator(SecureRandom random, int length) {
        this.random = random;
        this.length = Math.max(10, Math.min(16, length));
    }

    public String generate() {
        char[] code = new char[length];
        for (int index = 0; index < code.length; index++) {
            code[index] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }

    public String normalize(String candidate) {
        String normalized = candidate == null ? "" : candidate
                .toUpperCase(Locale.ROOT)
                .replaceAll("[-\\s]", "")
                .trim();
        if (!VALID.matcher(normalized).matches()) {
            throw invalidCode();
        }
        return normalized;
    }

    public BaseException invalidCode() {
        return new BaseException(
                ErrorCode.ENTITY_NOT_FOUND,
                "The meeting code is invalid or unavailable.");
    }
}
