package com.wannaverse.service;

import com.github.dockerjava.api.model.Container;
import com.wannaverse.persistence.DatabaseBackup;
import com.wannaverse.persistence.DatabaseBackupRepository;
import com.wannaverse.persistence.DatabaseServer;
import com.wannaverse.persistence.DatabaseServerRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Backups of the PostgreSQL servers: whole servers (pg_dumpall) and single databases (pg_dump
 * custom format), on a schedule with retention, kept in Docker Manager's data directory and
 * optionally copied to S3-compatible storage. Restores take a safety backup first.
 */
@Service
public class DatabaseBackupService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupService.class);
    private static final Pattern CREATE_DATABASE =
            Pattern.compile("^CREATE DATABASE (\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)[ ;]");
    private static final Set<String> SYSTEM_DATABASES =
            Set.of("postgres", "template0", "template1");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int KEEP_BEFORE_RESTORE = 3;

    private final DatabaseBackupRepository backups;
    private final DatabaseServerRepository servers;
    private final PostgresService postgres;
    private final DockerHostService dockerHostService;
    private final EncryptionService encryptionService;
    private final NotificationService notificationService;
    private final Path root;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    public DatabaseBackupService(
            DatabaseBackupRepository backups,
            DatabaseServerRepository servers,
            PostgresService postgres,
            DockerHostService dockerHostService,
            EncryptionService encryptionService,
            @org.springframework.context.annotation.Lazy NotificationService notificationService,
            @Value("${app.data-dir:database}") String dataDir) {
        this.notificationService = notificationService;
        this.backups = backups;
        this.servers = servers;
        this.postgres = postgres;
        this.dockerHostService = dockerHostService;
        this.encryptionService = encryptionService;
        this.root = Path.of(dataDir).toAbsolutePath().resolve("db-backups");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterrupted() {
        for (DatabaseBackup b : backups.findByStatus("RUNNING")) {
            b.setStatus("FAILED");
            b.setError("Docker Manager restarted while this was running");
            b.setFinishedAt(System.currentTimeMillis());
            backups.save(b);
            deleteQuietly(b);
            notifyFailed(b, false);
        }
        for (DatabaseServer s : servers.findAll()) {
            if (s.getActivity() != null) {
                s.setLastResult(
                        s.getActivity() + " was interrupted when Docker Manager restarted.");
                s.setLastResultFailed(true);
                s.setActivity(null);
                servers.save(s);
            }
        }
    }

    /** Backups kept from an earlier server of the same name on the same host come back. */
    public void adopt(DatabaseServer s) {
        for (DatabaseBackup b :
                backups.findByDockerHostIdAndServerName(s.getDockerHostId(), s.getName())) {
            if (!servers.existsById(b.getServerId())) {
                b.setServerId(s.getId());
                backups.save(b);
            }
        }
    }

    public List<DatabaseBackup> list(String serverId) {
        return backups.findByServerIdOrderByCreatedAtDesc(serverId);
    }

    public DatabaseBackup get(String backupId) {
        return backups.findById(backupId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such backup"));
    }

    /**
     * Removes a server's backups (files, copies and records), when the server is removed with them.
     */
    public void deleteAll(String serverId) {
        for (DatabaseBackup b : backups.findByServerIdOrderByCreatedAtDesc(serverId)) delete(b);
    }

    // --- taking backups ----------------------------------------------------------------------

    public DatabaseBackup startBackup(
            String serverId, String database, String origin, String user) {
        DatabaseServer s = postgres.require(serverId);
        if (database != null) PostgresService.ident(database, "database");
        String activity = database == null ? "Backing up the server" : "Backing up " + database;
        claim(s, activity, false);
        DatabaseBackup b;
        try {
            b =
                    newBackup(
                            s,
                            database == null ? "SERVER" : "DATABASE",
                            database,
                            database == null ? "SQL_GZ" : "CUSTOM",
                            origin,
                            user);
        } catch (RuntimeException e) {
            release(s, null, false);
            throw e;
        }
        executor.submit(
                () -> {
                    try {
                        runBackup(s, b);
                    } finally {
                        release(s, null, false);
                    }
                });
        return b;
    }

    private DatabaseBackup newBackup(
            DatabaseServer s,
            String scope,
            String database,
            String format,
            String origin,
            String user) {
        DatabaseBackup b = new DatabaseBackup();
        b.setServerId(s.getId());
        b.setDockerHostId(s.getDockerHostId());
        b.setServerName(s.getName());
        b.setScope(scope);
        b.setDatabaseName(database);
        b.setFormat(format);
        b.setOrigin(origin);
        b.setStatus("RUNNING");
        b.setCreatedAt(System.currentTimeMillis());
        b.setCreatedBy(user);
        String base =
                LocalDateTime.now().format(STAMP)
                        + "-"
                        + (database == null ? "server" : database)
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 6)
                        + ("CUSTOM".equals(format) ? ".dump" : ".sql.gz");
        b.setFileName(folder(s) + "/" + base);
        return backups.save(b);
    }

    /** Takes the backup; runs on the caller's thread. Returns true when it worked. */
    private boolean runBackup(DatabaseServer s, DatabaseBackup b) {
        DockerAPI api = postgres.api(s);
        String tmp = "/tmp/dm-backup-" + b.getId();
        try {
            Path file = file(b);
            Files.createDirectories(file.getParent());
            DockerAPI.ExecOutput out;
            String inContainer;
            if ("SERVER".equals(b.getScope())) {
                out =
                        api.execFull(
                                s.getContainerName(),
                                Map.of(),
                                6 * 3600,
                                "pg_dumpall",
                                "-U",
                                s.getSuperuser(),
                                "-f",
                                tmp + ".sql");
                if (out.ok()) {
                    out =
                            api.execFull(
                                    s.getContainerName(),
                                    Map.of(),
                                    3600,
                                    "gzip",
                                    "-f",
                                    tmp + ".sql");
                }
                inContainer = tmp + ".sql.gz";
            } else {
                out =
                        api.execFull(
                                s.getContainerName(),
                                Map.of(),
                                6 * 3600,
                                "pg_dump",
                                "-U",
                                s.getSuperuser(),
                                "-Fc",
                                "-d",
                                b.getDatabaseName(),
                                "-f",
                                tmp + ".dump");
                inContainer = tmp + ".dump";
            }
            if (!out.ok()) throw new IllegalStateException(PostgresService.clean(out.stderr()));
            b.setSizeBytes(api.getLargeFile(s.getContainerName(), inContainer, file));
            b.setStatus("DONE");
            b.setFinishedAt(System.currentTimeMillis());
            backups.save(b);
            copyToS3(s, b);
            if ("FAILED".equals(b.getS3Status())) notifyFailed(b, true);
            prune(s);
            return true;
        } catch (Exception e) {
            log.warn("Backup of {} failed: {}", s.getName(), e.getMessage());
            b.setStatus("FAILED");
            b.setError(trim(e.getMessage()));
            b.setFinishedAt(System.currentTimeMillis());
            backups.save(b);
            deleteQuietly(b);
            notifyFailed(b, false);
            return false;
        } finally {
            api.execFull(s.getContainerName(), Map.of(), 60, "sh", "-c", "rm -f " + tmp + ".*");
        }
    }

    /** Emails administrators who want to hear about failed backups (Settings, Notifications). */
    private void notifyFailed(DatabaseBackup b, boolean copyOnly) {
        try {
            notificationService.notifyDatabaseBackupFailed(b, copyOnly);
        } catch (Exception e) {
            log.warn("Could not send the backup failure email: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 3 * 60 * 1000)
    public void scheduled() {
        long now = System.currentTimeMillis();
        for (DatabaseServer s : servers.findByBackupIntervalHoursGreaterThan(0)) {
            if (now - s.getLastAutoBackupAt() < s.getBackupIntervalHours() * 3_600_000L) continue;
            if (busy.contains(s.getId())) continue;
            try {
                claim(s, "Backing up the server", false);
            } catch (ResponseStatusException e) {
                continue;
            }
            try {
                DatabaseBackup b = newBackup(s, "SERVER", null, "SQL_GZ", "SCHEDULED", "schedule");
                runBackup(s, b);
                DatabaseServer fresh = servers.findById(s.getId()).orElse(null);
                if (fresh != null) {
                    fresh.setLastAutoBackupAt(now);
                    servers.save(fresh);
                }
            } catch (Exception e) {
                log.warn("Scheduled backup of {} failed: {}", s.getName(), e.getMessage());
            } finally {
                release(s, null, false);
            }
        }
    }

    /** Keeps the newest automatic backups (and safety backups); manual ones stay until deleted. */
    private void prune(DatabaseServer s) {
        DatabaseServer fresh = servers.findById(s.getId()).orElse(s);
        int keep = Math.max(1, fresh.getBackupKeep());
        List<DatabaseBackup> auto =
                backups.findByServerIdAndOriginAndStatusOrderByCreatedAtDesc(
                        s.getId(), "SCHEDULED", "DONE");
        for (int i = keep; i < auto.size(); i++) delete(auto.get(i));
        List<DatabaseBackup> safety =
                backups.findByServerIdAndOriginAndStatusOrderByCreatedAtDesc(
                        s.getId(), "BEFORE_RESTORE", "DONE");
        for (int i = KEEP_BEFORE_RESTORE; i < safety.size(); i++) delete(safety.get(i));
    }

    // --- uploads and downloads ---------------------------------------------------------------

    /**
     * Stores an uploaded backup: plain SQL (gzipped or not) or a pg_dump custom-format file. A SQL
     * file that creates its own databases (pg_dumpall, or pg_dump --create) restores the whole
     * server; anything else is restored into a database you choose.
     */
    public DatabaseBackup upload(String serverId, String fileName, InputStream body, String user)
            throws IOException {
        DatabaseServer s = postgres.require(serverId);
        Path dir = root.resolve(folder(s));
        Files.createDirectories(dir);
        Path raw = Files.createTempFile(dir, "upload-", ".part");
        try {
            try (OutputStream out = Files.newOutputStream(raw)) {
                body.transferTo(out);
            }
            if (Files.size(raw) == 0) throw bad("The file is empty");
            byte[] head = new byte[5];
            try (InputStream in = Files.newInputStream(raw)) {
                int n = in.readNBytes(head, 0, 5);
                if (n < 2) throw bad("That isn't a PostgreSQL backup");
            }
            boolean gz = (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b;
            boolean custom = new String(head, StandardCharsets.US_ASCII).equals("PGDMP");
            String label =
                    fileName == null ? "upload" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
            if (label.length() > 80) label = label.substring(0, 80);

            DatabaseBackup b = new DatabaseBackup();
            b.setServerId(s.getId());
            b.setDockerHostId(s.getDockerHostId());
            b.setServerName(s.getName());
            b.setOrigin("UPLOAD");
            b.setNote(fileName);
            b.setCreatedAt(System.currentTimeMillis());
            b.setCreatedBy(user);
            String base =
                    LocalDateTime.now().format(STAMP)
                            + "-"
                            + label.replaceFirst("\\.(sql\\.gz|sql|dump|gz)$", "")
                            + "-"
                            + UUID.randomUUID().toString().substring(0, 6);
            if (custom) {
                b.setFormat("CUSTOM");
                b.setScope("DATABASE");
                b.setFileName(folder(s) + "/" + base + ".dump");
                Files.move(raw, file(b));
            } else {
                b.setFormat("SQL_GZ");
                b.setFileName(folder(s) + "/" + base + ".sql.gz");
                Path target = file(b);
                if (gz) {
                    Files.move(raw, target);
                } else {
                    try (InputStream in = Files.newInputStream(raw);
                            OutputStream out =
                                    new GZIPOutputStream(Files.newOutputStream(target))) {
                        in.transferTo(out);
                    }
                    Files.deleteIfExists(raw);
                }
                List<String> created;
                try {
                    created = createdDatabases(target);
                } catch (IOException e) {
                    Files.deleteIfExists(target);
                    throw bad("That file couldn't be read as SQL: " + e.getMessage());
                }
                b.setScope(created.isEmpty() ? "DATABASE" : "SERVER");
            }
            b.setSizeBytes(Files.size(file(b)));
            b.setStatus("DONE");
            b.setFinishedAt(System.currentTimeMillis());
            return backups.save(b);
        } finally {
            Files.deleteIfExists(raw);
        }
    }

    public record Download(Path file, String name) {}

    public Download download(String backupId) {
        DatabaseBackup b = get(backupId);
        if (!"DONE".equals(b.getStatus())) throw bad("That backup isn't finished");
        Path file = file(b);
        if (!Files.exists(file))
            throw new ResponseStatusException(HttpStatus.GONE, "The backup file is missing");
        return new Download(file, b.getServerName() + "-" + file.getFileName());
    }

    public void delete(String backupId) {
        DatabaseBackup b = get(backupId);
        if ("RUNNING".equals(b.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That backup is still running");
        delete(b);
    }

    private void delete(DatabaseBackup b) {
        deleteQuietly(b);
        if (b.getS3Key() != null) {
            servers.findById(b.getServerId())
                    .ifPresent(
                            s -> {
                                try {
                                    if (s.isS3Enabled()) s3(s).delete(b.getS3Key());
                                } catch (Exception e) {
                                    log.warn(
                                            "Could not delete the copy {}: {}",
                                            b.getS3Key(),
                                            e.getMessage());
                                }
                            });
        }
        backups.delete(b);
    }

    // --- restoring ---------------------------------------------------------------------------

    public record RestoreRequest(String database, String owner, boolean newOwner) {}

    public record RestoreStarted(String owner, String password) {}

    /**
     * Restores a backup. A whole-server backup replaces every database it contains (others stay as
     * they are); a single-database backup replaces (or creates) the database you name, owned by the
     * user you choose. A safety backup of the whole server is taken first.
     */
    public RestoreStarted restore(String backupId, RestoreRequest request, String user) {
        DatabaseBackup b = get(backupId);
        if (!"DONE".equals(b.getStatus())) throw bad("That backup isn't finished");
        DatabaseServer s = postgres.require(b.getServerId());
        Target target = target(s, b.getScope(), request);
        String label =
                b.getDatabaseName() != null
                        ? b.getDatabaseName()
                        : b.getNote() != null ? b.getNote() : "backup";
        claim(
                s,
                "Restoring "
                        + ("SERVER".equals(b.getScope())
                                ? "the server from " + label
                                : label + " into " + target.database),
                true);
        String password;
        try {
            password = target.prepare();
        } catch (RuntimeException e) {
            release(s, null, false);
            throw e;
        }
        executor.submit(
                () -> {
                    String result = null;
                    boolean failed = true;
                    try {
                        result = runRestore(s, b, target, user);
                        failed = false;
                    } catch (Exception e) {
                        log.warn("Restore into {} failed: {}", s.getName(), e.getMessage());
                        result = trim(e.getMessage());
                    } finally {
                        release(s, result, failed);
                    }
                });
        return new RestoreStarted(target.owner, password);
    }

    /** Where a database-level restore goes, and who owns it. */
    private final class Target {
        final DatabaseServer s;
        final String database;
        final String owner;
        final boolean newOwner;

        Target(DatabaseServer s, String database, String owner, boolean newOwner) {
            this.s = s;
            this.database = database;
            this.owner = owner;
            this.newOwner = newOwner;
        }

        /** Creates the owner if asked; returns its password. */
        String prepare() {
            if (database == null || !newOwner) return null;
            return postgres.createRole(s.getId(), owner, null);
        }
    }

    private Target target(DatabaseServer s, String scope, RestoreRequest request) {
        if ("SERVER".equals(scope)) return new Target(s, null, null, false);
        if (request == null || request.database() == null)
            throw bad("Choose the database to restore into");
        String db = request.database().trim();
        PostgresService.ident(db, "database");
        if (SYSTEM_DATABASES.contains(db)) throw bad("Choose another database name");
        String owner =
                request.owner() == null || request.owner().isBlank()
                        ? s.getSuperuser()
                        : request.owner().trim();
        PostgresService.ident(owner, "user");
        DockerAPI api = postgres.api(s);
        boolean exists = postgres.roleExists(api, s, owner);
        if (request.newOwner() && exists)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A user named " + owner + " already exists");
        if (!request.newOwner() && !exists) throw bad("There's no user named " + owner);
        return new Target(s, db, owner, request.newOwner());
    }

    private String runRestore(DatabaseServer s, DatabaseBackup b, Target target, String user)
            throws IOException {
        DockerAPI api = postgres.api(s);
        Path file = localCopy(s, b);
        boolean temporary = !file.equals(file(b));
        // A safety net: the whole server as it is now.
        DatabaseBackup safety = newBackup(s, "SERVER", null, "SQL_GZ", "BEFORE_RESTORE", user);
        if (!runBackup(s, safety)) {
            if (temporary) Files.deleteIfExists(file);
            throw new IllegalStateException(
                    "The safety backup failed, so nothing was restored: "
                            + get(safety.getId()).getError());
        }
        String tmp = "/tmp/dm-restore-" + b.getId();
        String ext = "CUSTOM".equals(b.getFormat()) ? ".dump" : ".sql.gz";
        try {
            api.putLargeFile(s.getContainerName(), tmp + ext, file);
            if (!"CUSTOM".equals(b.getFormat())) {
                var unzip =
                        api.execFull(
                                s.getContainerName(),
                                Map.of(),
                                3600,
                                "gzip",
                                "-d",
                                "-f",
                                tmp + ext);
                if (!unzip.ok())
                    throw new IllegalStateException(
                            "Could not unpack the backup: " + unzip.stderr().strip());
            }
            if ("SERVER".equals(b.getScope())) {
                for (String db : createdDatabases(file)) {
                    if (SYSTEM_DATABASES.contains(db)) continue;
                    postgres.psqlOrThrow(
                            api,
                            s,
                            "postgres",
                            "DROP DATABASE IF EXISTS "
                                    + PostgresService.quote(db)
                                    + " WITH (FORCE)");
                }
                var out =
                        api.execFull(
                                s.getContainerName(),
                                Map.of(),
                                6 * 3600,
                                "psql",
                                "-U",
                                s.getSuperuser(),
                                "-X",
                                "-q",
                                "-o",
                                "/dev/null",
                                "-d",
                                "postgres",
                                "-f",
                                tmp + ".sql");
                // A backup from another server carries its administrator password; keep ours.
                String pw = encryptionService.decrypt(s.getSuperuserPassword());
                postgres.psqlOrThrow(
                        api,
                        s,
                        "postgres",
                        "ALTER ROLE "
                                + PostgresService.quote(s.getSuperuser())
                                + " PASSWORD '"
                                + PostgresService.scramVerifier(pw)
                                + "'");
                // Users that already exist keep existing (their password is still set from the
                // backup).
                return summary(
                        "Restored the server",
                        out,
                        Pattern.compile("ERROR:  role \"[^\"]+\" already exists"));
            }
            String db = PostgresService.quote(target.database);
            postgres.psqlOrThrow(
                    api, s, "postgres", "DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
            postgres.psqlOrThrow(
                    api,
                    s,
                    "postgres",
                    "CREATE DATABASE " + db + " OWNER " + PostgresService.quote(target.owner));
            postgres.psqlOrThrow(
                    api,
                    s,
                    "postgres",
                    "REVOKE CONNECT, TEMPORARY ON DATABASE " + db + " FROM PUBLIC");
            DockerAPI.ExecOutput out;
            if ("CUSTOM".equals(b.getFormat())) {
                out =
                        api.execFull(
                                s.getContainerName(),
                                Map.of(),
                                6 * 3600,
                                "pg_restore",
                                "-U",
                                s.getSuperuser(),
                                "-d",
                                target.database,
                                "--no-owner",
                                "--no-privileges",
                                tmp + ext);
            } else {
                out =
                        api.execFull(
                                s.getContainerName(),
                                Map.of(),
                                6 * 3600,
                                "psql",
                                "-U",
                                s.getSuperuser(),
                                "-X",
                                "-q",
                                "-o",
                                "/dev/null",
                                "-d",
                                target.database,
                                "-f",
                                tmp + ".sql");
            }
            if (!target.owner.equals(s.getSuperuser())) {
                postgres.psqlOrThrow(api, s, target.database, giveOwnership(target.owner));
            }
            // A dump from elsewhere names its old users ("ALTER TABLE ... OWNER TO old"); those
            // users don't exist here and everything is given to the new owner instead.
            return summary(
                    "Restored " + target.database + " (owner " + target.owner + ")",
                    out,
                    Pattern.compile("ERROR:  role \"[^\"]+\" does not exist"));
        } finally {
            api.execFull(s.getContainerName(), Map.of(), 60, "sh", "-c", "rm -f " + tmp + ".*");
            if (temporary) Files.deleteIfExists(file);
        }
    }

    /**
     * Hands every object the restore created (as the administrator) to the database's owner, so the
     * app's user can use and change its own tables. Objects that belong to extensions stay.
     */
    static String giveOwnership(String owner) {
        String me = "(select oid from pg_roles where rolname = current_user)";
        String userSchema = "n.nspname !~ '^pg_' and n.nspname <> 'information_schema'";
        String notExtension =
                " and not exists (select 1 from pg_depend d where d.objid = %s and d.deptype ="
                        + " 'e')";
        return "DO $dm$ DECLARE r record; o text := "
                + PostgresService.literal(owner)
                + "; BEGIN FOR r IN select format('ALTER SCHEMA %I OWNER TO %I', n.nspname, o) as q"
                + " from pg_namespace n where n.nspowner = "
                + me
                + " and "
                + userSchema
                + " and n.nspname <> 'public'"
                + notExtension.formatted("n.oid")
                + " union all select format('ALTER %s %I.%I OWNER TO %I', case c.relkind when 'v'"
                + " then 'VIEW' when 'm' then 'MATERIALIZED VIEW' when 'S' then 'SEQUENCE' when 'f'"
                + " then 'FOREIGN TABLE' else 'TABLE' end, n.nspname, c.relname, o) from pg_class c"
                + " join pg_namespace n on n.oid = c.relnamespace where c.relowner = "
                + me
                + " and c.relkind in ('r','p','v','m','S','f') and "
                + userSchema
                + notExtension.formatted("c.oid")
                + " union all select format('ALTER ROUTINE %I.%I(%s) OWNER TO %I', n.nspname,"
                + " p.proname, pg_get_function_identity_arguments(p.oid), o) from pg_proc p join"
                + " pg_namespace n on n.oid = p.pronamespace where p.proowner = "
                + me
                + " and "
                + userSchema
                + notExtension.formatted("p.oid")
                + " union all select format('ALTER %s %I.%I OWNER TO %I', case t.typtype when 'd'"
                + " then 'DOMAIN' else 'TYPE' end, n.nspname, t.typname, o) from pg_type t join"
                + " pg_namespace n on n.oid = t.typnamespace where t.typowner = "
                + me
                + " and t.typtype in ('d','e','r','m') and "
                + userSchema
                + notExtension.formatted("t.oid")
                + " LOOP BEGIN EXECUTE r.q; EXCEPTION WHEN others THEN NULL; END; END LOOP; END"
                + " $dm$";
    }

    private static String summary(String done, DockerAPI.ExecOutput out, Pattern expected) {
        List<String> errors = new ArrayList<>();
        for (String line : out.stderr().split("\n")) {
            if (!line.contains("ERROR:") && !line.startsWith("pg_restore: error")) continue;
            if (expected != null && expected.matcher(line).find()) continue;
            errors.add(line.replaceFirst("^psql:[^:]*:\\d+: ", "").strip());
        }
        if (out.exitCode() == -1) return done + ", but it timed out before finishing.";
        if (errors.isEmpty()) return done + ".";
        StringBuilder sb =
                new StringBuilder(done)
                        .append(" with ")
                        .append(errors.size())
                        .append(errors.size() == 1 ? " error" : " errors")
                        .append(" (the rest went in):");
        errors.stream().limit(8).forEach(e -> sb.append("\n").append(e));
        if (errors.size() > 8) sb.append("\n...");
        return sb.toString();
    }

    /** The databases a plain SQL backup creates itself (CREATE DATABASE lines). */
    static List<String> createdDatabases(Path sqlGz) throws IOException {
        List<String> names = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                new GZIPInputStream(Files.newInputStream(sqlGz), 1 << 16),
                                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("CREATE DATABASE ")) continue;
                Matcher m = CREATE_DATABASE.matcher(line);
                if (!m.lookingAt()) continue;
                String name = m.group(1);
                if (name.startsWith("\""))
                    name = name.substring(1, name.length() - 1).replace("\"\"", "\"");
                names.add(name);
            }
        }
        return names;
    }

    // --- importing from another PostgreSQL container ----------------------------------------

    public record Source(String id, String name, String image) {}

    /** Other PostgreSQL containers on the host, to import databases from. */
    public List<Source> sources(String hostId) {
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        Set<String> ours = new HashSet<>();
        servers.findByDockerHostIdOrderByNameAsc(hostId)
                .forEach(s -> ours.add(s.getContainerName()));
        List<Source> list = new ArrayList<>();
        for (Container c : api.listAllContainers()) {
            if (!"running".equals(c.getState()) || c.getNames() == null || c.getNames().length == 0)
                continue;
            String name = c.getNames()[0].replaceFirst("^/", "");
            String image = c.getImage() == null ? "" : c.getImage().toLowerCase(Locale.ROOT);
            if (ours.contains(name)) continue;
            if (image.contains("postgres")
                    || image.contains("postgis")
                    || image.contains("timescale")
                    || image.contains("pgvector")) {
                list.add(new Source(c.getId(), name, c.getImage()));
            }
        }
        list.sort(Comparator.comparing(Source::name));
        return list;
    }

    /** The source's databases, signing in the way its own environment says (POSTGRES_USER...). */
    public List<String> sourceDatabases(
            String hostId, String container, String user, String password) {
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        SourceLogin login = sourceLogin(api, container, user, password);
        var out =
                sourceExec(
                        api,
                        container,
                        login,
                        120,
                        "psql",
                        "-X",
                        "-At",
                        "-d",
                        "postgres",
                        "-c",
                        "select datname from pg_database where not datistemplate and datallowconn"
                                + " and datname <> 'postgres' order by 1");
        if (!out.ok())
            throw bad(
                    "Could not sign in to "
                            + container
                            + ": "
                            + PostgresService.clean(out.stderr()));
        List<String> list = new ArrayList<>();
        for (String line : out.stdoutText().split("\n"))
            if (!line.isBlank()) list.add(line.strip());
        return list;
    }

    public record ImportRequest(
            String container,
            String sourceDatabase,
            String user,
            String password,
            String database,
            String owner,
            boolean newOwner) {}

    /**
     * Copies a database out of another PostgreSQL container into this server (pg_dump in the
     * source, keep the dump as a backup, then restore it). The source is only read.
     */
    public RestoreStarted importDatabase(String serverId, ImportRequest request, String user) {
        DatabaseServer s = postgres.require(serverId);
        if (request.container() == null || request.sourceDatabase() == null)
            throw bad("Choose the container and database");
        DockerAPI api = postgres.api(s);
        SourceLogin login =
                sourceLogin(api, request.container(), request.user(), request.password());
        Target target =
                target(
                        s,
                        "DATABASE",
                        new RestoreRequest(
                                request.database(), request.owner(), request.newOwner()));
        claim(s, "Importing " + request.sourceDatabase() + " from " + request.container(), true);
        String password;
        try {
            password = target.prepare();
        } catch (RuntimeException e) {
            release(s, null, false);
            throw e;
        }
        executor.submit(
                () -> {
                    String result = null;
                    boolean failed = true;
                    try {
                        DatabaseBackup b =
                                newBackup(
                                        s,
                                        "DATABASE",
                                        request.sourceDatabase(),
                                        "CUSTOM",
                                        "IMPORT",
                                        user);
                        b.setNote(request.container() + " / " + request.sourceDatabase());
                        backups.save(b);
                        String tmp = "/tmp/dm-export-" + b.getId() + ".dump";
                        try {
                            var out =
                                    sourceExec(
                                            api,
                                            request.container(),
                                            login,
                                            6 * 3600,
                                            "pg_dump",
                                            "-Fc",
                                            "-d",
                                            request.sourceDatabase(),
                                            "-f",
                                            tmp);
                            if (!out.ok())
                                throw new IllegalStateException(
                                        "pg_dump failed: " + PostgresService.clean(out.stderr()));
                            Files.createDirectories(file(b).getParent());
                            b.setSizeBytes(api.getLargeFile(request.container(), tmp, file(b)));
                            b.setStatus("DONE");
                            b.setFinishedAt(System.currentTimeMillis());
                            backups.save(b);
                        } catch (RuntimeException | IOException e) {
                            b.setStatus("FAILED");
                            b.setError(trim(e.getMessage()));
                            b.setFinishedAt(System.currentTimeMillis());
                            backups.save(b);
                            deleteQuietly(b);
                            throw e;
                        } finally {
                            api.execFull(request.container(), Map.of(), 60, "rm", "-f", tmp);
                        }
                        result =
                                runRestore(s, b, target, user)
                                        .replaceFirst(
                                                "^Restored",
                                                "Imported "
                                                        + request.sourceDatabase()
                                                        + " from "
                                                        + request.container()
                                                        + " as");
                        failed = false;
                    } catch (Exception e) {
                        log.warn("Import into {} failed: {}", s.getName(), e.getMessage());
                        result = trim(e.getMessage());
                    } finally {
                        release(s, result, failed);
                    }
                });
        return new RestoreStarted(target.owner, password);
    }

    private record SourceLogin(String user, String password) {}

    private static SourceLogin sourceLogin(
            DockerAPI api, String container, String user, String password) {
        Map<String, String> env = new HashMap<>();
        var config = api.inspectContainer(container).getConfig();
        if (config.getEnv() != null) {
            for (String e : config.getEnv()) {
                int eq = e.indexOf('=');
                if (eq > 0) env.put(e.substring(0, eq), e.substring(eq + 1));
            }
        }
        String u =
                user != null && !user.isBlank()
                        ? user.trim()
                        : env.getOrDefault("POSTGRES_USER", "postgres");
        String p =
                password != null && !password.isEmpty() ? password : env.get("POSTGRES_PASSWORD");
        if (!u.matches("^[A-Za-z0-9_.@-]{1,63}$")) throw bad("Invalid user name");
        return new SourceLogin(u, p);
    }

    /** Runs a client tool in the source container: over its local socket, then over TCP. */
    private static DockerAPI.ExecOutput sourceExec(
            DockerAPI api, String container, SourceLogin login, long timeout, String... command) {
        Map<String, String> env =
                login.password() == null ? Map.of() : Map.of("PGPASSWORD", login.password());
        List<String> cmd = new ArrayList<>(List.of(command));
        cmd.addAll(1, List.of("-U", login.user()));
        var out = api.execFull(container, env, timeout, cmd.toArray(String[]::new));
        if (out.ok() || login.password() == null) return out;
        cmd.addAll(1, List.of("-h", "127.0.0.1"));
        var tcp = api.execFull(container, env, timeout, cmd.toArray(String[]::new));
        return tcp.ok() ? tcp : out;
    }

    // --- settings and S3 ---------------------------------------------------------------------

    public record Settings(
            Integer intervalHours,
            Integer keep,
            Boolean s3Enabled,
            String s3Endpoint,
            String s3Region,
            String s3Bucket,
            String s3Prefix,
            String s3AccessKey,
            String s3SecretKey) {}

    public DatabaseServer updateSettings(String serverId, Settings request) {
        DatabaseServer s = postgres.require(serverId);
        if (request.intervalHours() != null) {
            if (request.intervalHours() < 0 || request.intervalHours() > 24 * 30)
                throw bad("Choose an interval up to 30 days");
            if (s.getBackupIntervalHours() == 0 && request.intervalHours() > 0)
                s.setLastAutoBackupAt(System.currentTimeMillis());
            s.setBackupIntervalHours(request.intervalHours());
        }
        if (request.keep() != null) {
            if (request.keep() < 1 || request.keep() > 365)
                throw bad("Keep between 1 and 365 backups");
            s.setBackupKeep(request.keep());
        }
        if (request.s3Endpoint() != null) s.setS3Endpoint(blankToNull(request.s3Endpoint()));
        if (request.s3Region() != null) s.setS3Region(blankToNull(request.s3Region()));
        if (request.s3Bucket() != null) s.setS3Bucket(blankToNull(request.s3Bucket()));
        if (request.s3Prefix() != null)
            s.setS3Prefix(blankToNull(request.s3Prefix().replaceAll("^/+|/+$", "")));
        if (request.s3AccessKey() != null) s.setS3AccessKey(blankToNull(request.s3AccessKey()));
        if (request.s3SecretKey() != null && !request.s3SecretKey().isBlank()) {
            s.setS3SecretKey(encryptionService.encrypt(request.s3SecretKey().trim()));
        }
        if (request.s3Enabled() != null) {
            if (request.s3Enabled()) s3(s); // validates the settings
            s.setS3Enabled(request.s3Enabled());
        }
        return servers.save(s);
    }

    /** Writes and deletes a small test object with the saved settings. */
    public void testS3(String serverId) {
        DatabaseServer s = postgres.require(serverId);
        S3Storage s3 = s3(s);
        String key = key(s, ".docker-manager-test");
        try {
            s3.putBytes(key, "Docker Manager connection test".getBytes(StandardCharsets.UTF_8));
            s3.delete(key);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    private S3Storage s3(DatabaseServer s) {
        try {
            String secret =
                    s.getS3SecretKey() == null
                            ? null
                            : encryptionService.decrypt(s.getS3SecretKey());
            return new S3Storage(
                    s.getS3Endpoint(),
                    s.getS3Region(),
                    s.getS3Bucket(),
                    s.getS3AccessKey(),
                    secret);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    private void copyToS3(DatabaseServer s, DatabaseBackup b) {
        DatabaseServer fresh = servers.findById(s.getId()).orElse(s);
        if (!fresh.isS3Enabled()) return;
        String key = key(fresh, b.getFileName());
        try {
            fresh = servers.findById(s.getId()).orElse(s);
            s3(fresh).put(key, file(b), sha256(file(b)));
            b.setS3Key(key);
            b.setS3Status("DONE");
            b.setS3Error(null);
        } catch (Exception e) {
            log.warn("Copying backup {} to S3 failed: {}", b.getId(), e.getMessage());
            b.setS3Status("FAILED");
            b.setS3Error(trim(e.getMessage()));
        }
        backups.save(b);
    }

    /** Copies one backup to S3 again (after fixing the settings, for example). */
    public DatabaseBackup retryS3(String backupId) {
        DatabaseBackup b = get(backupId);
        DatabaseServer s = postgres.require(b.getServerId());
        if (!s.isS3Enabled()) throw bad("Turn on copies to S3 first");
        if (!Files.exists(file(b)))
            throw new ResponseStatusException(HttpStatus.GONE, "The backup file is missing");
        copyToS3(s, b);
        return get(backupId);
    }

    /** The backup file, fetched back from S3 when only the copy is left. */
    private Path localCopy(DatabaseServer s, DatabaseBackup b) throws IOException {
        Path file = file(b);
        if (Files.exists(file)) return file;
        if (b.getS3Key() == null || !s.isS3Enabled())
            throw new IllegalStateException("The backup file is missing");
        Path tmp =
                Files.createTempFile(
                        file.getParent() == null ? root : file.getParent(), "s3-", ".tmp");
        s3(s).get(b.getS3Key(), tmp);
        return tmp;
    }

    private static String key(DatabaseServer s, String name) {
        String prefix = s.getS3Prefix() == null ? "" : s.getS3Prefix() + "/";
        return prefix + name;
    }

    // --- helpers -----------------------------------------------------------------------------

    /**
     * Runs a change that restarts the server (such as a new port) on the caller's thread, while no
     * backup, restore or import uses it, and keeps those from starting until it's done.
     */
    public <T> T whileIdle(String serverId, String activity, Supplier<T> work) {
        DatabaseServer s = postgres.require(serverId);
        claim(s, activity, true);
        try {
            return work.get();
        } finally {
            release(s, null, false);
        }
    }

    private void claim(DatabaseServer s, String activity, boolean exclusive) {
        if (!busy.add(s.getId())) {
            DatabaseServer fresh = servers.findById(s.getId()).orElse(s);
            String what =
                    fresh.getActivity() == null
                            ? "something else"
                            : fresh.getActivity().toLowerCase(Locale.ROOT);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The server is busy (" + what + "). Try again when it's done.");
        }
        DatabaseServer fresh = servers.findById(s.getId()).orElse(s);
        fresh.setActivity(activity);
        servers.save(fresh);
    }

    private void release(DatabaseServer s, String result, boolean failed) {
        try {
            servers.findById(s.getId())
                    .ifPresent(
                            fresh -> {
                                fresh.setActivity(null);
                                if (result != null) {
                                    fresh.setLastResult(result);
                                    fresh.setLastResultFailed(failed);
                                }
                                servers.save(fresh);
                            });
        } finally {
            busy.remove(s.getId());
        }
    }

    private static String folder(DatabaseServer s) {
        return s.getName()
                + "-"
                + s.getDockerHostId().substring(0, Math.min(8, s.getDockerHostId().length()));
    }

    private Path file(DatabaseBackup b) {
        Path p = root.resolve(b.getFileName()).normalize();
        if (!p.startsWith(root)) throw new IllegalStateException("Invalid backup path");
        return p;
    }

    private void deleteQuietly(DatabaseBackup b) {
        try {
            if (b.getFileName() != null) Files.deleteIfExists(file(b));
        } catch (Exception e) {
            log.debug("Could not delete {}: {}", b.getFileName(), e.getMessage());
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trim(String s) {
        if (s == null) return "Unknown error";
        return s.length() > 1900 ? s.substring(0, 1900) + "..." : s;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
