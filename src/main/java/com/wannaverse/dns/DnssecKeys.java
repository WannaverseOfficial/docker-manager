package com.wannaverse.dns;

import org.xbill.DNS.DClass;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Name;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * DNSSEC keys: one ECDSA P-256 key per zone (algorithm 13, the one RFC 8624 recommends), used as a
 * combined signing key (flags 257, the SEP bit set), so the registrar needs a single DS record.
 */
public final class DnssecKeys {

    public static final int ALGORITHM = DNSSEC.Algorithm.ECDSAP256SHA256;

    /** Zone key + secure entry point. */
    public static final int FLAGS = 257;

    private DnssecKeys() {}

    public static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create a DNSSEC key: " + e.getMessage(), e);
        }
    }

    public static String encodePrivate(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static String encodePublic(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static ZoneSource.SigningKey load(Name zone, String publicB64, String privateB64) {
        try {
            KeyFactory f = KeyFactory.getInstance("EC");
            PublicKey pub =
                    f.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicB64)));
            PrivateKey priv =
                    f.generatePrivate(
                            new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateB64)));
            return new ZoneSource.SigningKey(dnskey(zone, pub), priv);
        } catch (Exception e) {
            throw new IllegalStateException("The zone's DNSSEC key can't be read: " + e, e);
        }
    }

    public static DNSKEYRecord dnskey(Name zone, PublicKey pub) {
        try {
            return new DNSKEYRecord(
                    zone,
                    DClass.IN,
                    ZoneBuilder.DNSKEY_TTL,
                    FLAGS,
                    DNSKEYRecord.Protocol.DNSSEC,
                    ALGORITHM,
                    pub);
        } catch (DNSSEC.DNSSECException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The DS record to give the registrar (SHA-256 digest). */
    public static DSRecord ds(DNSKEYRecord key) {
        return new DSRecord(
                key.getName(), DClass.IN, ZoneBuilder.NS_TTL, DSRecord.Digest.SHA256, key);
    }
}
