package com.wannaverse.service;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.StringReader;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Checks for uploaded certificates, so a mismatched key or hostname is caught before nginx. */
final class CertificateChecks {
    private CertificateChecks() {}

    static PrivateKey readPrivateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (obj instanceof PEMKeyPair pair)
                return converter.getPrivateKey(pair.getPrivateKeyInfo());
            if (obj instanceof PrivateKeyInfo info) return converter.getPrivateKey(info);
            throw new IllegalArgumentException(
                    "The private key must be an unencrypted PEM key (BEGIN PRIVATE KEY, BEGIN RSA"
                            + " PRIVATE KEY or BEGIN EC PRIVATE KEY)");
        }
    }

    /** Signs a test value with the key and verifies it with the certificate. */
    static void requireMatchingKey(X509Certificate cert, String keyPem) throws Exception {
        PrivateKey key = readPrivateKey(keyPem);
        String algorithm =
                switch (key.getAlgorithm()) {
                    case "RSA" -> "SHA256withRSA";
                    case "EC", "ECDSA" -> "SHA256withECDSA";
                    case "Ed25519", "EdDSA" -> "Ed25519";
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported key type: " + key.getAlgorithm());
                };
        byte[] probe = "docker-manager".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(key);
        signer.update(probe);
        byte[] sig = signer.sign();
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(cert.getPublicKey());
        verifier.update(probe);
        if (!verifier.verify(sig)) {
            throw new IllegalArgumentException(
                    "The private key does not belong to this certificate");
        }
    }

    /** The certificate must name the hostname (exactly, or through a single-level wildcard). */
    static void requireHostname(X509Certificate cert, String hostname) throws Exception {
        String host = hostname.toLowerCase(Locale.ROOT);
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans != null) {
            for (List<?> san : sans) {
                if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
                    String name = String.valueOf(san.get(1)).toLowerCase(Locale.ROOT);
                    if (name.equals(host)) return;
                    if (name.startsWith("*.")
                            && host.endsWith(name.substring(1))
                            && host.indexOf('.') == host.length() - name.length() + 1) {
                        return;
                    }
                }
            }
        }
        throw new IllegalArgumentException("This certificate is not valid for " + hostname);
    }
}
