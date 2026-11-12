package com.wannaverse.iac.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.IacJson;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.DatabaseServer;
import com.wannaverse.persistence.DatabaseServerRepository;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.service.DatabaseBackupService;
import com.wannaverse.service.PostgresService;

import lombok.Data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code databases}: PostgreSQL servers, with their backups, users (roles), databases, access and
 * the containers that may reach them.
 *
 * <p>Roles and databases listed are created when missing, but never dropped by the file: removing
 * one from the file leaves it (and its data) alone. Drop them on the server's page.
 */
@Component
public class DatabasesHandler implements ResourceHandler<DatabasesHandler.DatabaseSpec> {

    private static final Logger log = LoggerFactory.getLogger(DatabasesHandler.class);
    private static final Pattern SERVER_NAME = Pattern.compile("^[a-z][a-z0-9-]{1,30}[a-z0-9]$");
    private static final Pattern IDENT = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final Set<String> SYSTEM_DATABASES =
            Set.of("postgres", "template0", "template1");

    /** What removing the entry from the file does to something that holds data. */
    public enum OnRemove {
        /** Leave it running; Docker Manager only stops managing it. */
        KEEP,
        /** Remove its containers, but keep the data (volumes, and backups for databases). */
        DELETE,
        /** Remove it together with its data. */
        DELETE_DATA
    }

    public enum Address {
        LOCALHOST,
        ALL
    }

    public enum AccessLevel {
        NONE,
        READ,
        WRITE
    }

    @Data
    @Doc("A PostgreSQL server in its own container, with its own network and data volume")
    public static class DatabaseSpec {
        @Doc("The Docker host (a key of hosts:, or a host's name). Default: the only host.")
        private String host;

        @Doc(
                "Server name: 3 to 32 lowercase letters, digits or dashes. Defaults to the entry's"
                        + " key.")
        private String name;

        @Doc(
                "PostgreSQL major version (18, 17 or 16; default 18). Can't be changed after"
                        + " creating: back up, create a new server and restore.")
        private String version;

        @Doc(
                "Password of the postgres administrator. Left out: generated when created, and an"
                        + " existing one is kept.")
        private Secret superuserPassword;

        @Doc("Publish PostgreSQL on this host port (1024-65535). Left out: not published.")
        private Integer port;

        @Doc(
                "Where the published port listens: localhost (127.0.0.1, default) or all (every"
                        + " interface)")
        private Address address;

        @Doc("Automatic backups (default every 24 hours, keeping 7)")
        private BackupSpec backups;

        @Doc("Copy backups to S3-compatible storage")
        private S3Spec s3;

        @Doc(
                "Login users, created when missing. Never dropped by the file. A password given"
                        + " here is set whenever it changes.")
        private Map<String, RoleSpec> roles = new LinkedHashMap<>();

        @Doc("Databases, created when missing. Never dropped by the file.")
        private Map<String, DbSpec> databases = new LinkedHashMap<>();

        @Doc(
                "Containers (names) connected to the server's network, so they can reach it at"
                        + " dm-pg-<name>:5432. Containers not listed are left connected.")
        private List<String> containers = new ArrayList<>();

        @Doc(
                "When the entry is removed from the file: keep (default; it keeps running, no"
                        + " longer managed), delete (remove the server, keep its data volume and"
                        + " backups) or delete-data (remove everything). Takes effect once applied,"
                        + " so apply it before removing the entry.")
        private OnRemove onRemove;
    }

    @Data
    public static class BackupSpec {
        @Doc("Hours between automatic backups, 0 for none (default 24)")
        private Integer intervalHours;

        @Doc("How many automatic backups to keep, 1-365 (default 7)")
        private Integer keep;
    }

    @Data
    public static class S3Spec {
        @Doc("Copy backups to S3 (default true)")
        private Boolean enabled;

        @Doc("Endpoint URL, for storage other than AWS (such as https://s3.example.com)")
        private String endpoint;

        @Doc("Region, such as eu-west-1")
        private String region;

        @Doc(value = "Bucket name", required = true)
        private String bucket;

        @Doc("Folder in the bucket")
        private String prefix;

        @Doc("Access key id")
        private String accessKey;

        @Doc("Secret access key. Left out: the saved one is kept.")
        private Secret secretKey;
    }

    @Data
    public static class RoleSpec {
        @Doc(
                "The user's password (at least 8 characters). Left out: generated when created"
                        + " (shown once after applying), and an existing one is kept.")
        private Secret password;
    }

    @Data
    public static class DbSpec {
        @Doc(
                "The user who owns it, used when creating it. Default: a user of the same name,"
                        + " created with a generated password when missing.")
        private String owner;

        @Doc("Access for other users: none, read or write")
        private Map<String, AccessLevel> access = new LinkedHashMap<>();
    }

    private final DatabaseServerRepository servers;
    private final DockerHostRepository hosts;
    private final PostgresService postgres;
    private final DatabaseBackupService backups;

    private final com.wannaverse.service.DockerHostService dockerHosts;

    public DatabasesHandler(
            DatabaseServerRepository servers,
            DockerHostRepository hosts,
            PostgresService postgres,
            DatabaseBackupService backups,
            com.wannaverse.service.DockerHostService dockerHosts) {
        this.dockerHosts = dockerHosts;
        this.servers = servers;
        this.hosts = hosts;
        this.postgres = postgres;
        this.backups = backups;
    }

    @Override
    public String section() {
        return "databases";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public Class<DatabaseSpec> specType() {
        return DatabaseSpec.class;
    }

    @Override
    public String description() {
        return "PostgreSQL servers with their backups, users and databases";
    }

    @Override
    public void validate(Entry<DatabaseSpec> entry, ApplyContext ctx) {
        DatabaseSpec s = entry.spec();
        String name = entry.nameOr(s.getName());
        if (!SERVER_NAME.matcher(name).matches()) {
            throw new IacException(
                    "name: use 3 to 32 lowercase letters, digits or dashes, starting with a letter"
                            + " (set name: when the key isn't one)");
        }
        if (s.getVersion() != null && !PostgresService.VERSIONS.contains(s.getVersion().trim())) {
            throw new IacException(
                    "version: choose " + String.join(", ", PostgresService.VERSIONS));
        }
        if (s.getPort() != null && (s.getPort() < 1024 || s.getPort() > 65535)) {
            throw new IacException("port: choose a port between 1024 and 65535");
        }
        if (s.getAddress() != null && s.getPort() == null) {
            throw new IacException("address: only used with port");
        }
        if (s.getBackups() != null) {
            Integer h = s.getBackups().getIntervalHours();
            Integer k = s.getBackups().getKeep();
            if (h != null && (h < 0 || h > 24 * 30)) {
                throw new IacException("backups.intervalHours: between 0 and 720");
            }
            if (k != null && (k < 1 || k > 365)) {
                throw new IacException("backups.keep: between 1 and 365");
            }
        }
        if (s.getS3() != null
                && (s.getS3().getBucket() == null || s.getS3().getBucket().isBlank())) {
            throw new IacException("s3.bucket: missing");
        }
        for (var r : s.getRoles().entrySet()) {
            ident(r.getKey(), "roles");
            if (r.getKey().equals("postgres")) {
                throw new IacException(
                        "roles: postgres is the administrator; use superuserPassword");
            }
            Secret pw = r.getValue() == null ? null : r.getValue().getPassword();
            if (pw != null && pw.value() != null && pw.value().length() < 8) {
                throw new IacException("roles." + r.getKey() + ".password: at least 8 characters");
            }
        }
        if (s.getSuperuserPassword() != null
                && s.getSuperuserPassword().value() != null
                && s.getSuperuserPassword().value().length() < 8) {
            throw new IacException("superuserPassword: at least 8 characters");
        }
        for (var d : s.getDatabases().entrySet()) {
            ident(d.getKey(), "databases");
            if (SYSTEM_DATABASES.contains(d.getKey())) {
                throw new IacException("databases: " + d.getKey() + " is reserved");
            }
            DbSpec db = d.getValue();
            if (db == null) continue;
            if (db.getOwner() != null) ident(db.getOwner(), "databases." + d.getKey() + ".owner");
            for (String role : db.getAccess().keySet()) {
                ident(role, "databases." + d.getKey() + ".access");
            }
        }
        String hostId = ctx.hostId(s.getHost());
        if (hostId != null) {
            // Two entries for the same server would fight over it.
            for (var other : ctx.records(section())) {
                if (!other.getEntryKey().equals(entry.key())
                        && hostId.equals(other.getHostId())
                        && name.equals(other.getLabel())
                        && ctx.declared(section(), other.getEntryKey())) {
                    throw new IacException(
                            "the entry " + other.getEntryKey() + " already manages this server");
                }
            }
        }
    }

    private static void ident(String name, String where) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IacException(
                    where
                            + ": \""
                            + name
                            + "\" must be lowercase letters, digits and underscores, starting with"
                            + " a letter");
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<DatabaseSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        if (hostId == null) return Optional.empty();
        String name = entry.nameOr(entry.spec().getName());
        return servers.findByDockerHostIdOrderByNameAsc(hostId).stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .map(DatabasesHandler::target);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return servers.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<DatabaseSpec> entry, ApplyContext ctx) {
        DatabaseSpec s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        String name = entry.nameOr(s.getName());
        DatabaseServer server =
                postgres.create(
                        hostId,
                        new PostgresService.CreateRequest(
                                name, version(s), s.getPort(), address(s)));
        backups.adopt(server);
        String id = server.getId();
        if (s.getSuperuserPassword() != null && !s.getSuperuserPassword().isBlank()) {
            postgres.setPassword(id, server.getSuperuser(), s.getSuperuserPassword().value());
        }
        backups.updateSettings(id, settings(s));
        syncInside(id, name, s, null, true, ctx);
        return target(postgres.require(id));
    }

    @Override
    public Target update(
            Entry<DatabaseSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        DatabaseSpec s = entry.spec();
        String id = record.getTargetId();
        DatabaseServer server = postgres.require(id);
        String name = entry.nameOr(s.getName());
        if (!server.getName().equals(name)) {
            throw new IacException(
                    "name: a server can't be renamed (it is "
                            + server.getName()
                            + "). Create a new entry and move the data with a backup.");
        }
        String hostId = ctx.hostId(s.getHost());
        if (hostId != null && !hostId.equals(server.getDockerHostId())) {
            throw new IacException(
                    "host: a server can't move to another host. Create a new entry there and move"
                            + " the data with a backup.");
        }
        if (s.getVersion() != null && !server.getImage().equals(image(s.getVersion().trim()))) {
            throw new IacException(
                    "version: the server runs "
                            + server.getImage()
                            + "; changing the major version needs a backup and restore into a new"
                            + " server");
        }
        if (changes.has("port", "address")) {
            backups.whileIdle(
                    id,
                    "Changing the port",
                    () -> postgres.changePort(id, s.getPort(), address(s)));
        }
        if (changes.has("superuserPassword")
                && s.getSuperuserPassword() != null
                && !s.getSuperuserPassword().isBlank()) {
            postgres.setPassword(id, server.getSuperuser(), s.getSuperuserPassword().value());
        }
        if (changes.has("backups", "s3")) backups.updateSettings(id, settings(s));
        JsonNode applied = null;
        if (!changes.takeover() && record.getAppliedSpec() != null) {
            try {
                applied = IacJson.JSON.readTree(record.getAppliedSpec());
            } catch (Exception e) {
                applied = null;
            }
        }
        if (changes.has("roles", "databases", "containers")) {
            syncInside(id, name, s, applied, changes.takeover(), ctx);
        }
        return target(postgres.require(id));
    }

    /** Roles, then databases and their access, then connected containers. */
    private void syncInside(
            String id,
            String name,
            DatabaseSpec s,
            JsonNode applied,
            boolean everything,
            ApplyContext ctx) {
        Set<String> roles = new HashSet<>();
        for (PostgresService.Role r : postgres.roles(id)) roles.add(r.name());

        JsonNode appliedRoles = applied == null ? null : applied.get("roles");
        for (var e : s.getRoles().entrySet()) {
            String role = e.getKey();
            Secret pw = e.getValue() == null ? null : e.getValue().getPassword();
            boolean given = pw != null && !pw.isBlank();
            if (!roles.contains(role)) {
                String set = postgres.createRole(id, role, given ? pw.value() : null);
                roles.add(role);
                if (!given) ctx.note("Database " + name + ", user " + role + ": password " + set);
            } else if (given) {
                JsonNode before = appliedRoles == null ? null : appliedRoles.get(role);
                JsonNode now = IacJson.JSON.valueToTree(e.getValue());
                if (everything || !Objects.equals(before, now)) {
                    postgres.setPassword(id, role, pw.value());
                }
            }
        }

        Map<String, PostgresService.DatabaseInfo> existing = new LinkedHashMap<>();
        for (PostgresService.DatabaseInfo d : postgres.databases(id)) existing.put(d.name(), d);
        for (var e : s.getDatabases().entrySet()) {
            String db = e.getKey();
            DbSpec spec = e.getValue() == null ? new DbSpec() : e.getValue();
            if (!existing.containsKey(db)) {
                String owner = spec.getOwner() == null ? db : spec.getOwner();
                boolean newUser = !roles.contains(owner);
                if (newUser && spec.getOwner() != null) {
                    throw new IacException(
                            "databases."
                                    + db
                                    + ".owner: there is no user "
                                    + owner
                                    + " (add it under roles:)");
                }
                PostgresService.Created c = postgres.createDatabase(id, db, owner, newUser);
                if (newUser) {
                    roles.add(owner);
                    ctx.note(
                            "Database "
                                    + name
                                    + ", user "
                                    + c.user()
                                    + " (owner of "
                                    + db
                                    + "): password "
                                    + c.password());
                }
            }
            for (var a : spec.getAccess().entrySet()) {
                postgres.setAccess(id, db, a.getKey(), a.getValue().name());
            }
        }

        if (!s.getContainers().isEmpty()) {
            Set<String> members = new HashSet<>();
            for (PostgresService.Member m : postgres.members(id)) members.add(m.name());
            for (String c : s.getContainers()) {
                if (members.contains(c)) continue;
                try {
                    postgres.connect(id, c);
                } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                    // A later section may create it: the check after the apply connects it then,
                    // and reports it (as a warning) if it never appears.
                }
            }
        }
    }

    /** Listed containers that are not on the server's network (re-created, or made later). */
    @Override
    public Set<String> drift(Entry<DatabaseSpec> entry, ManagedResource record, ApplyContext ctx) {
        List<String> wanted = entry.spec().getContainers();
        if (wanted.isEmpty() || !servers.existsById(record.getTargetId())) return Set.of();
        Set<String> members = new HashSet<>();
        for (PostgresService.Member m : postgres.members(record.getTargetId())) {
            members.add(m.name());
        }
        return DockerEntries.memberDrift(
                dockerHosts.getDockerAPI(postgres.require(record.getTargetId()).getDockerHostId()),
                wanted,
                members);
    }

    @Override
    public boolean deletesOnRemove(ManagedResource record, DatabaseSpec appliedSpec) {
        return appliedSpec != null
                && appliedSpec.getOnRemove() != null
                && appliedSpec.getOnRemove() != OnRemove.KEEP;
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (!servers.existsById(record.getTargetId())) return;
        boolean data = false;
        try {
            DatabaseSpec applied =
                    IacJson.SPEC.readValue(record.getAppliedSpec(), DatabaseSpec.class);
            data = applied.getOnRemove() == OnRemove.DELETE_DATA;
        } catch (Exception e) {
            log.debug(
                    "Could not read the applied entry of {}: {}",
                    record.getEntryKey(),
                    e.getMessage());
        }
        if (data) backups.deleteAll(record.getTargetId());
        postgres.remove(record.getTargetId(), data);
    }

    @Override
    public Map<String, DatabaseSpec> export(ExportContext ctx) {
        Map<String, DatabaseSpec> out = new LinkedHashMap<>();
        Map<String, Integer> nameCount = new LinkedHashMap<>();
        List<DatabaseServer> all = servers.findAll();
        for (DatabaseServer s : all) nameCount.merge(s.getName(), 1, Integer::sum);
        for (DatabaseServer server : all) {
            if (!hosts.existsById(server.getDockerHostId())) continue;
            String hostKey = ctx.hostKey(server.getDockerHostId());
            String fallback =
                    nameCount.get(server.getName()) > 1
                            ? hostKey + "-" + server.getName()
                            : server.getName();
            String key = ctx.key(section(), server.getId(), fallback);
            DatabaseSpec s = new DatabaseSpec();
            s.setHost(hostKey);
            if (!key.equals(server.getName())) s.setName(server.getName());
            s.setVersion(server.getImage().replaceFirst("^postgres:", "").replaceFirst("-.*$", ""));
            s.setPort(server.getPublishedPort());
            if (server.getPublishedPort() != null) {
                s.setAddress(
                        "0.0.0.0".equals(server.getPublishedAddress())
                                ? Address.ALL
                                : Address.LOCALHOST);
            }
            BackupSpec b = new BackupSpec();
            b.setIntervalHours(server.getBackupIntervalHours());
            b.setKeep(server.getBackupKeep());
            s.setBackups(b);
            if (server.getS3Bucket() != null) {
                S3Spec s3 = new S3Spec();
                s3.setEnabled(server.isS3Enabled());
                s3.setEndpoint(server.getS3Endpoint());
                s3.setRegion(server.getS3Region());
                s3.setBucket(server.getS3Bucket());
                s3.setPrefix(server.getS3Prefix());
                s3.setAccessKey(server.getS3AccessKey());
                if (server.hasS3Secret()) {
                    s3.setSecretKey(
                            ctx.secret(
                                    section(),
                                    key,
                                    "s3_secret_key",
                                    "S3 secret key of database server " + key));
                }
                s.setS3(s3);
            }
            try {
                for (PostgresService.Role r : postgres.roles(server.getId())) {
                    if (r.superuser() || !r.login() || r.name().equals(server.getSuperuser())) {
                        continue;
                    }
                    s.getRoles().put(r.name(), new RoleSpec());
                }
                for (PostgresService.DatabaseInfo d : postgres.databases(server.getId())) {
                    if (SYSTEM_DATABASES.contains(d.name())) continue;
                    DbSpec db = new DbSpec();
                    if (!d.owner().equals(d.name())) db.setOwner(d.owner());
                    for (PostgresService.Access a : d.access()) {
                        if ("PUBLIC".equals(a.level()) || a.role().equals(d.owner())) continue;
                        db.getAccess()
                                .put(
                                        a.role(),
                                        "WRITE".equals(a.level())
                                                ? AccessLevel.WRITE
                                                : AccessLevel.READ);
                    }
                    s.getDatabases().put(d.name(), db);
                }
                for (PostgresService.Member m : postgres.members(server.getId())) {
                    s.getContainers().add(m.name());
                }
            } catch (RuntimeException e) {
                // The server is down or its host unreachable: export its settings only.
                log.debug("Could not read {} for the export: {}", server.getName(), e.getMessage());
            }
            out.put(key, s);
        }
        return out;
    }

    private static String version(DatabaseSpec s) {
        return s.getVersion() == null ? null : s.getVersion().trim();
    }

    private static String image(String version) {
        return "postgres:" + version + "-alpine";
    }

    private static String address(DatabaseSpec s) {
        return s.getAddress() == Address.ALL ? "0.0.0.0" : "127.0.0.1";
    }

    private static DatabaseBackupService.Settings settings(DatabaseSpec s) {
        BackupSpec b = s.getBackups();
        Integer interval = b == null || b.getIntervalHours() == null ? 24 : b.getIntervalHours();
        Integer keep = b == null || b.getKeep() == null ? 7 : b.getKeep();
        S3Spec s3 = s.getS3();
        if (s3 == null) {
            return new DatabaseBackupService.Settings(
                    interval, keep, false, null, null, null, null, null, null);
        }
        return new DatabaseBackupService.Settings(
                interval,
                keep,
                s3.getEnabled() == null || s3.getEnabled(),
                s3.getEndpoint() == null ? "" : s3.getEndpoint(),
                s3.getRegion() == null ? "" : s3.getRegion(),
                s3.getBucket(),
                s3.getPrefix() == null ? "" : s3.getPrefix(),
                s3.getAccessKey() == null ? "" : s3.getAccessKey(),
                Secret.valueOf(s3.getSecretKey()));
    }

    private static Target target(DatabaseServer s) {
        return new Target(s.getId(), s.getDockerHostId(), s.getName());
    }
}
