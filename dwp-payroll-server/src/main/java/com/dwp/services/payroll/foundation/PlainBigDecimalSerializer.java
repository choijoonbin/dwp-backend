package com.dwp.services.payroll.foundation;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;

import java.io.IOException;
import java.math.BigDecimal;

/** Writes exact decimal values as non-exponent JSON strings. */
public final class PlainBigDecimalSerializer extends StdScalarSerializer<BigDecimal> {

    private static final long serialVersionUID = 1L;

    public PlainBigDecimalSerializer() {
        super(BigDecimal.class);
    }

    @Override
    public void serialize(
            BigDecimal value, JsonGenerator generator, SerializerProvider provider)
            throws IOException {
        generator.writeString(value.toPlainString());
    }
}
