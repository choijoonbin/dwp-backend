package com.dwp.services.approval.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Real local CompletableFuture cancellation; broker acknowledgement and message withdrawal are not asserted. */
class KafkaApprovalIntegrationCancellationTest {
    @SuppressWarnings("unchecked")
    final KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
    final KafkaApprovalIntegrationPublisher publisher=new KafkaApprovalIntegrationPublisher(kafka,"exact-topic");
    final ApprovalIntegrationOutboxRepository.PendingEvent event=new ApprovalIntegrationOutboxRepository.PendingEvent(UUID.randomUUID(),UUID.randomUUID(),42,UUID.randomUUID(),"approval.request.submitted","{\"current\":true}",1,"a".repeat(64),Instant.now().plusSeconds(30));

    @Test void timeoutCancelsOnlyItsCapturedFutureAndPreservesTenSecondDeadlineAndExactRecord() {
        var own=new CompletableFuture<SendResult<String,String>>() {
            @Override public SendResult<String,String> get(long time,TimeUnit unit) throws TimeoutException {
                assertEquals(10000L,time);assertEquals(TimeUnit.MILLISECONDS,unit);
                throw new TimeoutException("Explicit unit timeout boundary, not elapsed performance evidence");
            }
        };
        var foreign=new CompletableFuture<SendResult<String,String>>();
        when(kafka.send(anyRecord())).thenReturn(own);
        assertInstanceOf(TimeoutException.class,assertThrows(IllegalStateException.class,()->publisher.publish(event)).getCause());
        assertTrue(own.isCancelled());assertFalse(foreign.isCancelled());record();verify(kafka,times(1)).send(anyRecord());
    }

    @Test void actualInterruptedWaitCancelsCapturedFutureAndRestoresThreadInterruptWithoutRetry() {
        var own=new CompletableFuture<SendResult<String,String>>();when(kafka.send(anyRecord())).thenReturn(own);
        try {
            Thread.currentThread().interrupt();
            assertInstanceOf(InterruptedException.class,assertThrows(IllegalStateException.class,()->publisher.publish(event)).getCause());
            assertTrue(own.isCancelled());assertTrue(Thread.currentThread().isInterrupted());verify(kafka,times(1)).send(anyRecord());
        } finally {Thread.interrupted();}
    }

    @Test void completedExceptionalFutureRemainsCompletedRatherThanBeingCancelledOrRetried() {
        var own=new CompletableFuture<SendResult<String,String>>();own.completeExceptionally(new IllegalStateException("Broker rejected"));
        when(kafka.send(anyRecord())).thenReturn(own);
        assertInstanceOf(ExecutionException.class,assertThrows(IllegalStateException.class,()->publisher.publish(event)).getCause());
        assertFalse(own.isCancelled());verify(kafka,times(1)).send(anyRecord());
    }

    private static ProducerRecord<String,String> anyRecord() {return org.mockito.ArgumentMatchers.any();}

    @SuppressWarnings({"unchecked","rawtypes"})
    private void record() {
        ArgumentCaptor<ProducerRecord<String,String>> capture=ArgumentCaptor.forClass((Class)ProducerRecord.class);verify(kafka).send(capture.capture());
        var record=capture.getValue();assertEquals("exact-topic",record.topic());assertEquals(event.requestId().toString(),record.key());assertEquals(event.payload(),record.value());
        assertEquals(event.eventId().toString(),new String(record.headers().lastHeader("dwp-event-id").value(),StandardCharsets.UTF_8));
        assertEquals(event.eventType(),new String(record.headers().lastHeader("dwp-event-type").value(),StandardCharsets.UTF_8));
        assertEquals("42",new String(record.headers().lastHeader("dwp-tenant-id").value(),StandardCharsets.UTF_8));
    }
}
