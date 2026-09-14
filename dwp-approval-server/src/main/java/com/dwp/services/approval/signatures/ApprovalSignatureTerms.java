package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.signatures.ApprovalSignatureDtos.Terms;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** Explicitly provisioned terms, never fabricated or provisioned by a GET. */
public final class ApprovalSignatureTerms {
    private final String id;
    private final long version;
    private final Map<String,String> texts;
    private final Instant expires;
    private final Clock clock;
    public ApprovalSignatureTerms(String id, long version, Map<String,String> texts, Instant expires, Clock clock) {
        this.id=id; this.version=version; this.texts=Map.copyOf(texts); this.expires=expires; this.clock=clock;
    }
    public Terms current(String locale) {
        String text=texts.get(locale);
        if (id==null || !id.matches("[A-Za-z0-9._:-]{1,80}") || version<1 || version>ApprovalSignatureDtos.MAX_VERSION
                || !java.util.Set.of("ko","en").contains(locale) || text==null || text.isBlank()
                || text.length()>16000 || expires==null || !expires.isAfter(clock.instant())) throw unavailable();
        return new Terms(id,version,sha(text.getBytes(StandardCharsets.UTF_8)),locale,text,expires);
    }
    public void unchanged(Terms original) { if (!original.equals(current(original.locale()))) throw conflict(); }
}
