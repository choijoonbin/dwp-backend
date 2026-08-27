package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingJoinCodeGeneratorTest {

    @Test
    void createsHighEntropyHumanSafeCodes() {
        MeetingJoinCodeGenerator generator = new MeetingJoinCodeGenerator(new SecureRandom(), 12);
        Set<String> values = new HashSet<>();

        for (int index = 0; index < 500; index++) values.add(generator.generate());

        assertThat(values).hasSize(500);
        assertThat(values).allMatch(value -> value.matches("^[A-HJ-NP-Z2-9]{12}$"));
    }

    @Test
    void normalizesGroupingButReturnsTheSameFailureForMalformedCodes() {
        MeetingJoinCodeGenerator generator = new MeetingJoinCodeGenerator(new SecureRandom(), 12);

        assertThat(generator.normalize("abcd - efgh - lk"))
                .isEqualTo("ABCDEFGHLK");
        assertThat(generator.normalize("abcd-efgh-jklm-npqr"))
                .isEqualTo("ABCDEFGHJKLMNPQR");
        assertThatThrownBy(() -> generator.normalize("1234"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
        assertThatThrownBy(() -> generator.normalize("ABCDEFGHJKLMNPQRS"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
    }

    @Test
    void clampsConfiguredCodeLengthToTheContractBoundaries() {
        assertThat(new MeetingJoinCodeGenerator(new SecureRandom(), 9).generate()).hasSize(10);
        assertThat(new MeetingJoinCodeGenerator(new SecureRandom(), 17).generate()).hasSize(16);
    }
}
