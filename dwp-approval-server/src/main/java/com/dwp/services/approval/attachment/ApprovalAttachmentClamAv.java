package com.dwp.services.approval.attachment;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ApprovalAttachmentClamAv implements ApprovalAttachmentScanner {
    private final String host;
    private final int port;
    private final int timeoutMillis;
    private final Clock clock;
    private final Duration maxDefinitionsAge;
    public ApprovalAttachmentClamAv(String host, int port, int timeoutMillis, Duration maxDefinitionsAge, Clock clock) {
        if (host==null || host.isBlank() || port<1 || port>65535 || timeoutMillis<1 || timeoutMillis>60000 || maxDefinitionsAge.isNegative() || maxDefinitionsAge.isZero() || maxDefinitionsAge.compareTo(Duration.ofDays(2))>0)
            throw new IllegalArgumentException("Bounded private ClamAV configuration is required.");
        this.host=host; this.port=port; this.timeoutMillis=timeoutMillis; this.maxDefinitionsAge=maxDefinitionsAge; this.clock=clock;
    }
    private record Engine(String version, Instant definitionsAt) { }
    @Override public String readiness() { try { engine(); return "ENGINE_VERIFIED"; } catch (Exception failure) { return "SCANNER_UNAVAILABLE"; } }
    @Override public Result scan(byte[] bytes, String sha) {
        ApprovalAttachmentIntegrity.require(bytes,bytes.length,sha);
        try {
            Engine before=engine(); String verdict=exchange("zINSTREAM\0",bytes); Engine after=engine();
            if (!before.equals(after)) return result(Verdict.INDETERMINATE,"SCANNER_ENGINE_CHANGED",after,sha);
            if (verdict.equals("stream: OK")) return result(Verdict.AV_CLEAR,"AV_CLEAR_NOT_SANITIZED",after,sha);
            if (verdict.startsWith("stream: ") && verdict.endsWith(" FOUND") && !verdict.contains("Heuristics.Limits.Exceeded")) return result(Verdict.MALWARE,"MALWARE_DETECTED",after,sha);
            return result(Verdict.INDETERMINATE,"SCANNER_INCOMPLETE_OR_ERROR",after,sha);
        } catch (Exception failure) { return new Result(Verdict.INDETERMINATE,"SCANNER_UNAVAILABLE",null,null,clock.instant(),sha); }
    }
    private Result result(Verdict verdict, String reason, Engine engine, String sha) { return new Result(verdict,reason,engine.version(),engine.definitionsAt(),clock.instant(),sha); }
    private Engine engine() throws Exception {
        String[] fields=exchange("zVERSION\0",null).split("/",3);
        if (fields.length!=3 || !fields[0].matches("ClamAV [0-9.]+") || !fields[1].matches("[0-9]+")) throw new IllegalStateException("Unknown engine evidence.");
        Instant updated=ZonedDateTime.parse(fields[2].trim().replaceAll("\\s+"," "),DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy",Locale.ENGLISH).withZone(ZoneOffset.UTC)).toInstant();
        Duration age=Duration.between(updated,clock.instant());
        if (age.isNegative() || age.compareTo(maxDefinitionsAge)>0) throw new IllegalStateException("Scanner definitions are stale.");
        return new Engine(fields[0]+"/"+fields[1],updated);
    }
    private String exchange(String command, byte[] content) throws Exception {
        long deadline=System.nanoTime()+Duration.ofMillis(timeoutMillis).toNanos();
        try (var socket=new Socket(); var watchdog=Executors.newSingleThreadScheduledExecutor(runnable->{
            var thread=new Thread(runnable,"approval-clamav-deadline");thread.setDaemon(true);return thread;
        })) {
            var timeout=watchdog.schedule(()->{try {socket.close();} catch (java.io.IOException ignored) { }},timeoutMillis,TimeUnit.MILLISECONDS);
            try {
            socket.connect(new InetSocketAddress(host,port),timeoutMillis); socket.setSoTimeout(timeoutMillis);
            var output=new DataOutputStream(socket.getOutputStream()); output.write(command.getBytes(StandardCharsets.US_ASCII));
            if (content!=null) {
                for (int offset=0;offset<content.length;offset+=8192) {
                    if (System.nanoTime()>deadline) throw new java.io.IOException("Scanner deadline elapsed.");
                    int size=Math.min(8192,content.length-offset); output.writeInt(size); output.write(content,offset,size);
                }
                output.writeInt(0);
            }
            output.flush(); var result=new ByteArrayOutputStream(); int value;
            while ((value=socket.getInputStream().read())!=-1 && value!=0) {
                if (System.nanoTime()>deadline || result.size()>=4096 || value>127) throw new java.io.IOException("Invalid bounded scanner reply.");
                result.write(value);
            }
            if (value!=0) throw new java.io.IOException("Scanner reply is incomplete.");
            return result.toString(StandardCharsets.US_ASCII);
            } finally {timeout.cancel(false);watchdog.shutdownNow();}
        }
    }
}
