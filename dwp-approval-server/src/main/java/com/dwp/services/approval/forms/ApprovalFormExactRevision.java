package com.dwp.services.approval.forms;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigInteger;

/** Never coerce floating-point or string revisions through the global legacy mapper. */
public final class ApprovalFormExactRevision extends JsonDeserializer<Long> {
    private static final BigInteger MAX=BigInteger.valueOf(9007199254740991L);
    @Override public Long deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT) return (Long)context.handleUnexpectedToken(Long.class,parser);
        BigInteger number=parser.getBigIntegerValue();
        if(number.signum()<0||number.compareTo(MAX)>0) return (Long)context.handleWeirdNumberValue(Long.class,number,"Revision must be a nonnegative safe integer.");
        return number.longValueExact();
    }
}
