package com.wannaverse.iac;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * Reads a secret from any YAML scalar, so that {@code PORT: 8080}, {@code DEBUG: true} or a numeric
 * password work wherever a secret is expected.
 */
public class SecretDeserializer extends StdDeserializer<Secret> {

    public SecretDeserializer() {
        super(Secret.class);
    }

    @Override
    public Secret deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != null && p.currentToken().isScalarValue()) {
            return Secret.of(p.getValueAsString());
        }
        return (Secret) ctxt.handleUnexpectedToken(Secret.class, p);
    }
}
