package com.dwp.services.approval.attachment;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;

@Component
public class ApprovalAttachmentInput {
    private final ThreadPoolExecutor readers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>(),runnable->{
        var thread=new Thread(runnable,"approval-attachment-input");thread.setDaemon(true);return thread;
    });
    public byte[] read(InputStream input,long declaredSize,Duration timeout) throws IOException {
        if(input==null || declaredSize<1 || declaredSize>26214400 || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30))>0) throw new IOException("Invalid bounded attachment input.");
        Future<byte[]> result;
        try{result=readers.submit(()->input.readNBytes(Math.toIntExact(declaredSize+1)));}
        catch(RejectedExecutionException capacity){throw new IOException("Attachment input capacity is unavailable.",capacity);}
        try{return result.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        catch(TimeoutException deadline){result.cancel(true);input.close();throw new IOException("Attachment input deadline elapsed.",deadline);}
        catch(InterruptedException interrupted){result.cancel(true);input.close();Thread.currentThread().interrupt();throw new IOException("Attachment input was interrupted.",interrupted);}
        catch(ExecutionException failure){if(failure.getCause() instanceof IOException io) throw io;throw new IOException("Attachment input failed.",failure.getCause());}
    }
    @PreDestroy public void close(){readers.shutdownNow();}
}
