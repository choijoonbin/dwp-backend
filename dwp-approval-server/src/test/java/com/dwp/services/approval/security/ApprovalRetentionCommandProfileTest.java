package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos;
import com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import static org.mockito.Mockito.*;

import static com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandProfile.Operation;
import static org.assertj.core.api.Assertions.*;

class ApprovalRetentionCommandProfileTest {
    private static final ValidatorFactory VALIDATION=Validation.buildDefaultValidatorFactory();
    private static final UUID TARGET=UUID.fromString("00000000-0000-0000-0000-000000000042");
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final ApprovalStepUpVerifier verifier=new ApprovalStepUpVerifier(mapper,"","","dwp-approval-server","","urn:dwp:acr:mfa",600,900);
    private final ApprovalRetentionCommandProfile profile=new ApprovalRetentionCommandProfile(verifier,VALIDATION.getValidator(),mapper);

    @AfterAll static void closeValidation() {VALIDATION.close();}

    @ParameterizedTest @EnumSource(Operation.class)
    void computesOriginalTypedBodyWithTheActualStepUpAlgorithm(Operation operation) {
        Object body=body(operation,"original-key",17L);
        var prepared=profile.prepare(operation,target(operation),body);
        assertThat(prepared.requestBodySha256()).isEqualTo(verifier.payloadSha256(body));
        assertThat(prepared.operation()).isEqualTo(operation);
        assertThat(prepared.idempotencyKey()).isEqualTo("original-key");
        assertThat(prepared.algorithm()).isEqualTo(ApprovalRetentionCommandProfile.ALGORITHM);
        assertThat(prepared.originalExpectedVersion()).isEqualTo(operation==Operation.INITIALIZE_POLICY?null:17L);
    }

    @Test void hashesExactlySortedUtf8TypedJsonIncludingUnicodeAndSafeIntegerBoundary() throws Exception {
        var body=new ApprovalRetentionDtos.PublishPolicy(9_007_199_254_740_990L,"original-key","독립 검토 승인 완료입니다");
        String canonical="{\"expectedVersion\":9007199254740990,\"idempotencyKey\":\"original-key\",\"reviewComment\":\"독립 검토 승인 완료입니다\"}";
        var prepared=profile.prepare(Operation.PUBLISH_POLICY,TARGET,body);
        assertThat(prepared.requestBodySha256())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))));
        System.out.println("RETENTION_JAVA_GOLDEN="+mapper.writeValueAsString(Map.of(
                "algorithm",prepared.algorithm(),"profileVersion",ApprovalRetentionCommandProfile.VERSION,
                "operation",prepared.operation().name(),"originalTargetId",TARGET.toString(),
                "body",body,"expectedCanonical1",canonical,"requestBodySha256",prepared.requestBodySha256(),
                "provenance","ApprovalRetentionCommandProfileTest actual ApprovalStepUpVerifier.payloadSha256")));
    }

    @ParameterizedTest @MethodSource("textGoldenVectors")
    void actualMapperHashesEscapedTextAndPairedNonBmpWithoutChangingTheProfile(String name,String text,String escaped) throws Exception {
        var body=new ApprovalRetentionDtos.PublishPolicy(9_007_199_254_740_990L,"original-key",text);
        String canonical="{\"expectedVersion\":9007199254740990,\"idempotencyKey\":\"original-key\",\"reviewComment\":\""+escaped+"\"}";
        var prepared=profile.prepare(Operation.PUBLISH_POLICY,TARGET,body);
        var ordered=new java.util.TreeMap<String,Object>();ordered.put("expectedVersion",body.expectedVersion());
        ordered.put("idempotencyKey",body.idempotencyKey());ordered.put("reviewComment",body.reviewComment());
        assertThat(new String(mapper.writeValueAsBytes(ordered),StandardCharsets.UTF_8)).isEqualTo(canonical);
        assertThat(prepared.requestBodySha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))));
        assertThat(mapper.readTree(canonical).path("reviewComment").textValue()).isEqualTo(text);
        System.out.println("RETENTION_JAVA_GOLDEN2="+mapper.writeValueAsString(Map.of(
                "name",name,"algorithm",prepared.algorithm(),"profileVersion",ApprovalRetentionCommandProfile.VERSION,
                "operation",prepared.operation().name(),"originalTargetId",TARGET.toString(),"body",body,
                "expectedCanonical1",canonical,"requestBodySha256",prepared.requestBodySha256(),
                "provenance","ApprovalRetentionCommandProfileTest actual configured ApprovalStepUpVerifier.payloadSha256")));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> textGoldenVectors() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("escaped-quote-backslash-newline",
                        "Review \"quoted\" path \\approval\nApproved",
                        "Review \\\"quoted\\\" path \\\\approval\\nApproved"),
                org.junit.jupiter.params.provider.Arguments.of("paired-non-bmp",
                        "Review approved \uD83D\uDE80","Review approved \\uD83D\\uDE80"),
                org.junit.jupiter.params.provider.Arguments.of("paired-and-escaped",
                        "Review \uD83D\uDE80 \"quoted\" \\approval\nApproved",
                        "Review \\uD83D\\uDE80 \\\"quoted\\\" \\\\approval\\nApproved"),
                org.junit.jupiter.params.provider.Arguments.of("literal-backslash-u-not-a-scalar",
                        "Review literal \\uD83D\\uDE80 and \\u000b",
                        "Review literal \\\\uD83D\\\\uDE80 and \\\\u000b"),
                org.junit.jupiter.params.provider.Arguments.of("actual-c0-0b-0e-1a",
                        "Review controls \u000B \u000E \u001A approved",
                        "Review controls \\u000B \\u000E \\u001A approved"));
    }

    @ParameterizedTest @MethodSource("unpairedSurrogates")
    void loneUtf16SurrogatesAreInvalidBeforeTheVerifierCanDigest(String text) {
        var digest=spy(verifier);
        var guarded=new ApprovalRetentionCommandProfile(digest,VALIDATION.getValidator(),mapper);
        invalid(()->guarded.prepare(Operation.PUBLISH_POLICY,TARGET,
                new ApprovalRetentionDtos.PublishPolicy(17L,"original-key",text)));
        verify(digest,never()).payloadSha256(any());
    }

    static java.util.stream.Stream<String> unpairedSurrogates() {
        return java.util.stream.Stream.of("Review unpaired high \uD800","Review unpaired low \uDC00",
                "Review high before scalar \uD800x","Review low before pair \uDC00\uD83D\uDE80","Review interior NUL \u0000 approved");
    }

    @Test void textOnlyPredispatchValidationDoesNotTightenFrozenNumericKeysOrReplayBodies() {
        var digest=spy(verifier);var textOnly=new ApprovalRetentionCommandProfile(digest,VALIDATION.getValidator(),mapper);
        textOnly.validateText(new ApprovalRetentionDtos.PublishPolicy(Long.MAX_VALUE,".","Review paired \uD83D\uDE80 approved"));
        verifyNoInteractions(digest);
    }

    @Test void refusesUnsafeIntegersAndAnIncrementThatCannotBeRepresentedExactly() {
        invalid(()->profile.prepare(Operation.PUBLISH_POLICY,TARGET,body(Operation.PUBLISH_POLICY,"original-key",Long.MAX_VALUE)));
        invalid(()->profile.prepare(Operation.SAVE_POLICY,TARGET,body(Operation.SAVE_POLICY,"original-key",9_007_199_254_740_991L)));
        invalid(()->profile.prepare(Operation.CLAIM_RECORD,TARGET,new ApprovalRetentionDtos.CreateClaim(17L,TARGET,
                Long.MAX_VALUE,2L,"a".repeat(64),"original-key")));
    }

    @Test void typedNestedRulesAreOrderIndependentButNotArrayOrderIndependent() throws Exception {
        var first=mapper.readValue("{\"idempotencyKey\":\"original-key\",\"rules\":"+mapper.writeValueAsString(rules())+",\"expectedVersion\":17}",ApprovalRetentionDtos.SavePolicy.class);
        var second=body(Operation.SAVE_POLICY,"original-key",17L);
        assertThat(profile.prepare(Operation.SAVE_POLICY,TARGET,first).requestBodySha256())
                .isEqualTo(profile.prepare(Operation.SAVE_POLICY,TARGET,second).requestBodySha256());
        var changed=new ApprovalRetentionDtos.SavePolicy(17L,"original-key",new ApprovalRetentionDtos.PublicRules(false,
                List.of("CONFIDENTIAL","INTERNAL"),10,10,10,10,10,100,10));
        assertThat(profile.prepare(Operation.SAVE_POLICY,TARGET,first).requestBodySha256())
                .isNotEqualTo(profile.prepare(Operation.SAVE_POLICY,TARGET,changed).requestBodySha256());
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void refusesUntypedBodiesAndOtherOperationTypes(Operation operation) {
        invalid(()->profile.prepare(operation,target(operation),Map.of("idempotencyKey","original-key")));
        Operation other=operation==Operation.INITIALIZE_POLICY?Operation.SAVE_POLICY:Operation.INITIALIZE_POLICY;
        invalid(()->profile.prepare(operation,target(operation),body(other,"original-key",17L)));
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void refusesMissingOrInventedTargetAndInvalidKeys(Operation operation) {
        invalid(()->profile.prepare(operation,operation==Operation.INITIALIZE_POLICY?TARGET:null,body(operation,"original-key",17L)));
        invalid(()->profile.prepare(operation,target(operation),body(operation,"wrong key",17L)));
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void changedOriginalKeyOrVersionCannotHealTheOriginalDigest(Operation operation) {
        var original=profile.prepare(operation,target(operation),body(operation,"original-key",17L));
        assertThat(profile.prepare(operation,target(operation),body(operation,"another-key",17L)).requestBodySha256()).isNotEqualTo(original.requestBodySha256());
        if(operation!=Operation.INITIALIZE_POLICY) {
            assertThat(profile.prepare(operation,TARGET,body(operation,"original-key",18L)).requestBodySha256()).isNotEqualTo(original.requestBodySha256());
            invalid(()->profile.prepare(operation,TARGET,body(operation,"original-key",-1L)));
        }
    }

    @Test void rejectsUnknownFieldsEvenWithTheNormalPolicyMapper() {
        assertThatThrownBy(()->mapper.readValue("{\"expectedAbsent\":true,\"idempotencyKey\":\"original-key\",\"authority\":\"invented\"}",ApprovalRetentionDtos.InitializePolicy.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test void refusesUnknownStoredOperationInsteadOfInferringFromADigest() {
        assertThatThrownBy(()->profile.operation("retry-or-publish"))
                .isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private Object body(Operation operation,String key,long version) {
        return switch(operation) {
            case INITIALIZE_POLICY -> new ApprovalRetentionDtos.InitializePolicy(true,key);
            case SAVE_POLICY -> new ApprovalRetentionDtos.SavePolicy(version,key,rules());
            case PUBLISH_POLICY -> new ApprovalRetentionDtos.PublishPolicy(version,key,"Independent checker reviewed the original policy.");
            case CLAIM_RECORD -> new ApprovalRetentionDtos.CreateClaim(version,TARGET,3L,2L,"a".repeat(64),key);
        };
    }
    private UUID target(Operation operation) {return operation==Operation.INITIALIZE_POLICY?null:TARGET;}
    private ApprovalRetentionDtos.PublicRules rules() {
        return new ApprovalRetentionDtos.PublicRules(false,List.of("INTERNAL","CONFIDENTIAL"),10,10,10,10,10,100,10);
    }
    private void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }
}
