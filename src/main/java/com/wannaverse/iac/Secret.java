package com.wannaverse.iac;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A sensitive value in the configuration file: a password, private key or token.
 *
 * <p>It is never written back out in clear. Plans and the stored record of what was applied show a
 * keyed hash ({@code secret:3f9a...}), so a changed password still shows up as a change without
 * revealing it. Exports show a {@code ${env:...}} placeholder instead of the value.
 */
public final class Secret {

    private static volatile byte[] hashKey = "unset".getBytes(StandardCharsets.UTF_8);

    private final String value;
    private final String placeholder;

    private Secret(String value, String placeholder) {
        this.value = value;
        this.placeholder = placeholder;
    }

    @JsonCreator
    public static Secret of(String value) {
        return value == null ? null : new Secret(value, null);
    }

    /** A secret for an export: written out as the given text, such as {@code ${env:NAME}}. */
    public static Secret placeholder(String text) {
        return new Secret(null, text);
    }

    /** Set once at startup from the encryption key, so hashes differ between installations. */
    static void setHashKey(String key) {
        hashKey = key.getBytes(StandardCharsets.UTF_8);
    }

    /** The clear value. Null for an export placeholder. */
    public String value() {
        return value;
    }

    public boolean isBlank() {
        return value == null || value.isBlank();
    }

    /** Null-safe clear value of an optional secret. */
    public static String valueOf(Secret secret) {
        return secret == null ? null : secret.value;
    }

    @JsonValue
    public String json() {
        return placeholder != null ? placeholder : "secret:" + fingerprint();
    }

    private String fingerprint() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Secret s
                && Objects.equals(value, s.value)
                && Objects.equals(placeholder, s.placeholder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, placeholder);
    }

    @Override
    public String toString() {
        return placeholder != null ? placeholder : "****";
    }
}
