package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding.Intent;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding.Target;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Primitive binding mocks only; actual attachment preparation, Auth and PostgreSQL integration have separate gates. */
class ApprovalWorkflowInformationAttachmentsTest {
    static final UUID REQUEST=UUID.randomUUID(),FORM=UUID.randomUUID(),WORKFLOW=UUID.randomUUID(),PERSON=UUID.randomUUID();
    static final String HASH="a".repeat(64),SCHEMA="b".repeat(64);
    final ApprovalAttachmentLifecycleBinding binding=mock(ApprovalAttachmentLifecycleBinding.class);
    final ApprovalAttachmentLifecycleBinding.Pin pin=mock(ApprovalAttachmentLifecycleBinding.Pin.class);
    final ApprovalWorkflowInformationAttachments helper=new ApprovalWorkflowInformationAttachments(binding);
    final Target source=new Target(FORM,WORKFLOW,"DEFAULT",SCHEMA,1,HASH);
    final ApprovalWorkflowQuorumInformationContext.Bound bound=bound();

    @BeforeEach void before() {
        actor(42,99,PERSON);TransactionSynchronizationManager.setActualTransactionActive(true);
        when(binding.prepare(REQUEST,3,Intent.INFO_RESPONSE)).thenReturn(pin);
        when(binding.target(REQUEST)).thenReturn(source);
        when(pin.sourcePayloadRevision()).thenReturn(1);when(pin.sourcePayloadSha256()).thenReturn(HASH);
    }
    @AfterEach void after() {ApprovalRequestContext.clear();TransactionSynchronizationManager.clear();}

    @Test void preparedConstructorIsPrivateAndUnchangedReplySealsExactSource() {
        assertTrue(Stream.of(ApprovalWorkflowInformationAttachments.PreparedReply.class.getDeclaredConstructors())
                .allMatch(constructor->Modifier.isPrivate(constructor.getModifiers())));
        var prepared=helper.prepare(REQUEST,bound);assertFalse(prepared.materialChange());prepared.seal(1,HASH);
        verify(binding).seal(pin,source);
    }
    @Test void genuinePinMaterialRequiresNextRevisionEvenWithSamePayloadHash() {
        when(pin.materialChange()).thenReturn(true);var prepared=helper.prepare(REQUEST,bound);
        assertTrue(prepared.materialChange());assertConflict(()->prepared.seal(1,HASH));
        prepared.seal(2,HASH);verify(binding).seal(pin,new Target(FORM,WORKFLOW,"DEFAULT",SCHEMA,2,HASH));
    }
    @Test void payloadDeltaRequiresNextRevisionEvenWithoutAttachmentDelta() {
        var prepared=helper.prepare(REQUEST,bound);prepared.seal(2,"c".repeat(64));
        verify(binding).seal(pin,new Target(FORM,WORKFLOW,"DEFAULT",SCHEMA,2,"c".repeat(64)));
    }
    @ParameterizedTest @MethodSource("wrongTargets") void everyImmutableTargetFieldMustMatch(Target wrong) {
        when(binding.target(REQUEST)).thenReturn(wrong);assertConflict(()->helper.prepare(REQUEST,bound));
        verify(binding,never()).seal(any(),any());
    }
    static Stream<Target> wrongTargets() {
        return Stream.of(new Target(UUID.randomUUID(),WORKFLOW,"DEFAULT",SCHEMA,1,HASH),
                new Target(FORM,UUID.randomUUID(),"DEFAULT",SCHEMA,1,HASH),new Target(FORM,WORKFLOW,"OTHER",SCHEMA,1,HASH),
                new Target(FORM,WORKFLOW,"DEFAULT","c".repeat(64),1,HASH),new Target(FORM,WORKFLOW,"DEFAULT",SCHEMA,2,HASH),
                new Target(FORM,WORKFLOW,"DEFAULT",SCHEMA,1,"c".repeat(64)));
    }
    @Test void pinRevisionMustMatchLockedInformationContext() {
        when(pin.sourcePayloadRevision()).thenReturn(2);assertConflict(()->helper.prepare(REQUEST,bound));
        verify(binding,never()).target(any());
    }
    @Test void pinDigestMustMatchLockedInformationContext() {
        when(pin.sourcePayloadSha256()).thenReturn("c".repeat(64));assertConflict(()->helper.prepare(REQUEST,bound));
        verify(binding,never()).target(any());
    }
    @Test void missingPinCannotProducePreparedReply() {
        when(binding.prepare(REQUEST,3,Intent.INFO_RESPONSE)).thenReturn(null);assertConflict(()->helper.prepare(REQUEST,bound));
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3}) void requesterTenantPersonAndMissingPersonAreFailClosed(int variation) {
        actor(variation==0?43:42,variation==1?100:99,variation==2?UUID.randomUUID():variation==3?null:PERSON);
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->helper.prepare(REQUEST,bound)).getErrorCode());
        verifyNoInteractions(binding);
    }
    @Test void prepareRequiresWritableBusinessTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);assertConflict(()->helper.prepare(REQUEST,bound));
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertConflict(()->helper.prepare(REQUEST,bound));verifyNoInteractions(binding);
    }
    @Test void preparedReplyCannotCrossActorOrTransactionBoundary() {
        var prepared=helper.prepare(REQUEST,bound);actor(42,100,UUID.randomUUID());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->prepared.seal(1,HASH)).getErrorCode());
        actor(42,99,PERSON);TransactionSynchronizationManager.setActualTransactionActive(false);
        assertConflict(()->prepared.seal(1,HASH));verify(binding,never()).seal(any(),any());
    }
    @Test void invalidRevisionOrHashCannotSealAndSuccessfulSealIsSingleUse() {
        var prepared=helper.prepare(REQUEST,bound);assertConflict(()->prepared.seal(2,HASH));
        assertConflict(()->prepared.seal(1,null));assertConflict(()->prepared.seal(1,"not-a-digest"));
        prepared.seal(1,HASH);assertConflict(()->prepared.seal(1,HASH));verify(binding,times(1)).seal(pin,source);
    }
    @Test void bindingFailureIsNotReportedAsSuccessfulSeal() {
        doThrow(new BaseException(ErrorCode.RESOURCE_CONFLICT)).when(binding).seal(pin,source);
        var prepared=helper.prepare(REQUEST,bound);assertConflict(()->prepared.seal(1,HASH));
    }
    static void actor(long tenant,long id,UUID person) {
        ApprovalRequestContext.set(id,tenant,person,"Primitive subject",Set.of("WORKSPACE_MEMBER"),Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:UPDATE"));
    }
    static ApprovalWorkflowQuorumInformationContext.Bound bound() {
        var pins=new ApprovalWorkflowQuorum.Pins(42,WORKFLOW,2,"d".repeat(64),SCHEMA,1,"e".repeat(64));
        var policy=new ApprovalWorkflowQuorumRuntimeStore.Policy(1,pins.policySha256(),3,50,100,List.of());
        var context=new ApprovalWorkflowQuorumRuntimeStore.Context(pins,FORM,99,PERSON,1,HASH,3,policy);
        var definition=ApprovalWorkflowQuorumDefinition.fromStages(60,List.of(new ApprovalWorkflowQuorumDefinition.Stage("REVIEW","Review","REVIEWER",
                new ApprovalWorkflowQuorum.Rule(ApprovalWorkflowQuorum.Mode.ANY,null),15,List.of())));
        return new ApprovalWorkflowQuorumInformationContext.Bound(context,definition,3,"{}",Map.of(),"DEFAULT","DEFAULT","{}",UUID.randomUUID(),UUID.randomUUID());
    }
    static void assertConflict(org.junit.jupiter.api.function.Executable action) {
        assertEquals(ErrorCode.RESOURCE_CONFLICT,assertThrows(BaseException.class,action).getErrorCode());
    }
}
