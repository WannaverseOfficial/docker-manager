package com.wannaverse.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Makes sure the server never runs with a guessable signing or encryption secret.
 *
 * <p>Runs before Spring starts. For each secret, a value from the environment is used when it is
 * strong. When it is missing, a placeholder, too short, or one of the example values that were
 * published in this repository's README and docker-compose.yml (and are therefore public), a random
 * value is generated once and stored in {@code <data dir>/.secrets.properties}, so it survives
 * restarts.
 *
 * <p>The encryption key protects data already stored in the database (git tokens, registry and SMTP
 * passwords). Replacing it would make that data unreadable, so a weak encryption key that is
 * already in use is kept and a loud warning is logged instead. JWT secrets are always safe to
 * replace: the only effect is that everyone signs in again.
 */
public final class SecretsBootstrap {
    static final int MIN_LENGTH = 32;

    /** Example values that appeared in this project's public README and docker-compose.yml. */
    static final Set<String> PUBLISHED_VALUES =
            Set.of(
                    "bI4pEE+5oVq9gkWyy/plIsRvaZ8ghj54access",
                    "bI4pEE+5oVq9gkWyy/plIsRvaZ8ghj54refresh",
                    "bI4pEE+5oVq9gkWyy/plIsRvaZ8ghj54");

    private static final String FILE_NAME = ".secrets.properties";

    /** application.yaml's old fallback, used by installs that never set ENCRYPTION_KEY. */
    static final String LEGACY_DEFAULT_ENCRYPTION_KEY = "change-this-in-production-32chars";

    private SecretsBootstrap() {}

    public static void apply() {
        Path dataDir = dataDir();
        Path file = dataDir.resolve(FILE_NAME);
        Properties stored = load(file);
        boolean changed = false;

        changed |= ensure(stored, "JWT_ACCESS_SECRET", "app.jwt.access-secret", true);
        changed |= ensure(stored, "JWT_REFRESH_SECRET", "app.jwt.refresh-secret", true);
        changed |= ensure(stored, "ENCRYPTION_KEY", "app.encryption.key", false);

        String access = System.getProperty("app.jwt.access-secret", env("JWT_ACCESS_SECRET"));
        String refresh = System.getProperty("app.jwt.refresh-secret", env("JWT_REFRESH_SECRET"));
        if (access != null && access.equals(refresh)) {
            String fresh = random();
            stored.setProperty("JWT_REFRESH_SECRET", fresh);
            System.setProperty("app.jwt.refresh-secret", fresh);
            changed = true;
            warn("JWT_REFRESH_SECRET was the same as JWT_ACCESS_SECRET; using a generated one.");
        }

        if (changed) {
            save(dataDir, file, stored);
        }
    }

    /**
     * Resolves one secret. Returns true when the stored file changed.
     *
     * @param replaceable whether a weak value from the environment may be swapped for a generated
     *     one (true for JWT secrets, false for the encryption key, see class comment)
     */
    private static boolean ensure(
            Properties stored, String envName, String property, boolean replaceable) {
        String fromEnv = env(envName);
        if (isStrong(fromEnv)) {
            return false;
        }

        String fromFile = stored.getProperty(envName);

        if (fromEnv != null && !fromEnv.isBlank() && !replaceable) {
            // A weak encryption key is set. Data may already be encrypted with it: keep it.
            warn(
                    envName
                            + " is weak or was copied from the public example. Data encrypted with"
                            + " it can be decrypted by anyone who has the database file. Set a new"
                            + " random value of at least 32 characters and re-enter stored"
                            + " credentials (git tokens, registry and SMTP passwords).");
            return false;
        }

        if (fromEnv != null && !fromEnv.isBlank()) {
            warn(
                    envName
                            + " is weak or was copied from the public example, so anyone could"
                            + " forge sign-in tokens. Using a generated secret stored in the data"
                            + " directory instead.");
        }

        if (isStrong(fromFile)) {
            System.setProperty(property, fromFile);
            return false;
        }

        if (!replaceable && Files.exists(dataDir().resolve("data.db"))) {
            // An existing install that never set ENCRYPTION_KEY has been encrypting with the
            // built-in default. Keep using it so stored credentials stay readable.
            warn(
                    envName
                            + " is not set, so the old built-in default is still used to read"
                            + " existing credentials. Set ENCRYPTION_KEY to a random value of 32+"
                            + " characters only together with re-entering stored credentials.");
            System.setProperty(property, LEGACY_DEFAULT_ENCRYPTION_KEY);
            return false;
        }

        String generated = random();
        stored.setProperty(envName, generated);
        System.setProperty(property, generated);
        return true;
    }

    static boolean isStrong(String value) {
        if (value == null || value.isBlank() || value.length() < MIN_LENGTH) {
            return false;
        }
        if (PUBLISHED_VALUES.contains(value)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return !lower.startsWith("change-this") && !lower.startsWith("changeme");
    }

    private static Path dataDir() {
        String configured = env("APP_DATA_DIR");
        return Path.of(configured != null && !configured.isBlank() ? configured : "database");
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    private static String random() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Properties load(Path file) {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                warn("Could not read " + file + ": " + e.getMessage());
            }
        }
        return properties;
    }

    private static void save(Path dir, Path file, Properties properties) {
        try {
            Files.createDirectories(dir);
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "Generated by Docker Manager. Keep this file private.");
            }
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX file system (e.g. Windows during development).
            }
        } catch (IOException e) {
            // The generated values are still applied for this run; they just won't survive a
            // restart.
            warn("Could not save generated secrets to " + file + ": " + e.getMessage());
        }
    }

    private static void warn(String message) {
        System.err.println("[security] WARNING: " + message);
    }
}
