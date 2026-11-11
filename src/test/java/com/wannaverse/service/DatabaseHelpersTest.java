package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

class DatabaseHelpersTest {
    private static final String FS = "\u001f";
    private static final String RS = "\u001e";

    @Test
    void parsesResultSetsAndStatusLines() {
        // Shape psql prints with -A -F \x1f -R \x1e -P footer=on (checked against PostgreSQL 18).
        String out =
                "id"
                        + FS
                        + "name"
                        + RS
                        + "1"
                        + FS
                        + "a"
                        + RS
                        + "2"
                        + FS
                        + "multi\nline"
                        + RS
                        + "3"
                        + FS
                        + "\u0001"
                        + RS
                        + "(3 rows)\n"
                        + "INSERT 0 1\n"
                        + "?column?"
                        + RS
                        + "(0 rows)\n";
        List<PostgresService.ResultSet> results = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        PostgresService.parse(out, results, messages);

        assertEquals(2, results.size());
        var first = results.get(0);
        assertEquals(List.of("id", "name"), first.columns());
        assertEquals(3, first.rowCount());
        assertEquals("multi\nline", first.rows().get(1).get(1));
        assertNull(first.rows().get(2).get(1), "the null marker becomes null");
        assertFalse(first.rowsCut());
        assertEquals(0, results.get(1).rowCount());
        assertEquals(List.of("INSERT 0 1"), messages);
    }

    @Test
    void marksCutOutput() {
        String out = "a" + RS + "1" + RS + "2"; // no footer: the output was cut off
        List<PostgresService.ResultSet> results = new ArrayList<>();
        PostgresService.parse(out, results, new ArrayList<>());
        assertTrue(results.getFirst().rowsCut());
        assertEquals(2, results.getFirst().rowCount());
    }

    @Test
    void scramVerifierMatchesRfc7677() throws Exception {
        String v = PostgresService.scramVerifier("pencil-and-paper");
        var m =
                java.util.regex.Pattern.compile("^SCRAM-SHA-256\\$4096:([^$]+)\\$([^:]+):(.+)$")
                        .matcher(v);
        assertTrue(m.matches(), v);
        byte[] salt = Base64.getDecoder().decode(m.group(1));
        assertEquals(16, salt.length);
        // Recompute independently with the JDK's PBKDF2.
        var factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] salted =
                factory.generateSecret(
                                new javax.crypto.spec.PBEKeySpec(
                                        "pencil-and-paper".toCharArray(), salt, 4096, 256))
                        .getEncoded();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salted, "HmacSHA256"));
        byte[] clientKey = mac.doFinal("Client Key".getBytes(StandardCharsets.UTF_8));
        byte[] serverKey = mac.doFinal("Server Key".getBytes(StandardCharsets.UTF_8));
        byte[] stored = java.security.MessageDigest.getInstance("SHA-256").digest(clientKey);
        assertEquals(Base64.getEncoder().encodeToString(stored), m.group(2));
        assertEquals(Base64.getEncoder().encodeToString(serverKey), m.group(3));
    }

    @Test
    void findsDatabasesADumpCreates(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("all.sql.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(
                    ("CREATE ROLE app;\n"
                         + "CREATE DATABASE app1 WITH TEMPLATE = template0 ENCODING = 'UTF8';\n"
                         + "CREATE DATABASE \"My \"\"odd\"\" db\" WITH TEMPLATE = template0;\n"
                         + "CREATE DATABASE template1 WITH TEMPLATE = template0;\n"
                         + "-- CREATE DATABASE commented WITH x;\n"
                         + "INSERT INTO t VALUES ('CREATE DATABASE nope WITH');\n")
                            .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(
                List.of("app1", "My \"odd\" db", "template1"),
                DatabaseBackupService.createdDatabases(file));
    }

    @Test
    void ownershipBlockNamesTheOwnerOnce() {
        String sql = DatabaseBackupService.giveOwnership("app_user");
        assertTrue(sql.startsWith("DO $dm$"));
        assertTrue(sql.contains("o text := 'app_user';"));
        assertFalse(sql.contains("n.n."));
    }

    @Test
    void identifiersAndLiterals() {
        assertEquals("\"a\"\"b\"", PostgresService.quote("a\"b"));
        assertEquals("'it''s'", PostgresService.literal("it's"));
        assertThrows(RuntimeException.class, () -> PostgresService.ident("Bad-Name", "database"));
        assertThrows(RuntimeException.class, () -> PostgresService.ident("x; drop", "database"));
        PostgresService.ident("my_app2", "database");
        assertThrows(RuntimeException.class, () -> PostgresService.checkPassword("short"));
        assertThrows(
                RuntimeException.class, () -> PostgresService.checkPassword("has a space in it"));
    }

    @Test
    void s3KeysAreEncodedPerSegment() {
        assertEquals(
                "backups/main-1234/20260101-file.sql.gz",
                S3Storage.encodePath("backups/main-1234/20260101-file.sql.gz"));
        assertEquals("a%20b/c%2Bd~", S3Storage.encodePath("a b/c+d~"));
        // SHA-256 of nothing, used for requests without a body.
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                S3Storage.sha256(new byte[0]));
    }
}
