package com.dwp.services.approval.attachment;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.*;

class ApprovalAttachmentInputTest {
    @Test void actualByteStreamIsHardCappedBeforeStorage() throws Exception {
        var reader=new ApprovalAttachmentInput();try{assertThat(reader.read(new ByteArrayInputStream(new byte[]{1,2,3}),1,Duration.ofSeconds(1))).containsExactly(1,2);}finally{reader.close();}
    }
    @Test void blockedInputDeadlineClosesSourceWithoutCreatingUnboundedReaders() {
        var reader=new ApprovalAttachmentInput();var closed=new CountDownLatch(1);
        var input=new InputStream(){@Override public int read() throws IOException {try{closed.await();return -1;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}}@Override public void close(){closed.countDown();}};
        try{assertThatThrownBy(()->reader.read(input,1,Duration.ofMillis(50))).isInstanceOf(IOException.class);assertThat(closed.getCount()).isZero();}finally{reader.close();}
    }
    @Test void invalidSizeAndUnboundedTimeoutNeverEnterReaderPool() {
        var reader=new ApprovalAttachmentInput();try{assertThatThrownBy(()->reader.read(new ByteArrayInputStream(new byte[]{1}),26214401,Duration.ofSeconds(1))).isInstanceOf(IOException.class);
            assertThatThrownBy(()->reader.read(new ByteArrayInputStream(new byte[]{1}),1,Duration.ofMinutes(1))).isInstanceOf(IOException.class);}finally{reader.close();}
    }
}
