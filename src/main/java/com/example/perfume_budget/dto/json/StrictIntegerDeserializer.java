package com.example.perfume_budget.dto.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.io.IOException;

/**
 * API-04 rework: a JSON integer and nothing else. Jackson's defaults would turn {@code 1.5} into 1 and {@code "7"}
 * into 7; this rejects both, and rejects any number outside the int range (e.g. {@code 1E999999999},
 * {@code 99999999999}) without converting it. The error message is fixed text plus the field name, never the
 * submitted value, and is returned as the 400 description (see GlobalExceptionHandler).
 */
public class StrictIntegerDeserializer extends StdDeserializer<Integer> {

    public StrictIntegerDeserializer() {
        super(Integer.class);
    }

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String field = p.currentName() == null ? "Value" : p.currentName();
        if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            if (p.getNumberType() == JsonParser.NumberType.INT) {
                return p.getIntValue();
            }
            throw new InvalidWholeNumberException(p, field + " is out of range");
        }
        if (p.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
            // never converted: 1E999999999 is refused without building the number
            throw new InvalidWholeNumberException(p, field + " must be a whole number");
        }
        throw new InvalidWholeNumberException(p, field + " must be a whole number (a JSON number, not text)");
    }

    /** Carries a client-safe message for the 400 body. */
    public static class InvalidWholeNumberException extends MismatchedInputException {
        public InvalidWholeNumberException(JsonParser p, String message) {
            super(p, message, Integer.class);
        }
    }
}
