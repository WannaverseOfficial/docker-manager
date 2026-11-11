package com.wannaverse.service;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HealthCheck;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
import com.wannaverse.persistence.DatabaseServer;
import com.wannaverse.persistence.DatabaseServerRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * PostgreSQL servers run by Docker Manager, with secure defaults:
 *
 * <ul>
 *   <li>a random 32-character superuser password, SCRAM-SHA-256 for every network login;
 *   <li>no port on the host unless asked for; apps reach it on its own Docker network;
 *   <li>each app database is closed to everyone but its owner and the users given access;
 *   <li>passwords are sent to PostgreSQL as SCRAM verifiers, so they never appear in its logs.
 * </ul>
 *
 * <p>Docker Manager talks to the server with psql inside the container over the local socket.
 */
@Service
public class PostgresService {
    private static final Logger log = LoggerFactory.getLogger(PostgresService.class);
    public static final List<String> VERSIONS = List.of("18", "17", "16");
    static final String LABEL = "com.wannaverse.database";
    private static final Pattern SERVER_NAME = Pattern.compile("^[a-z][a-z0-9-]{1,30}[a-z0-9]$");
    private static final Pattern IDENT = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final Pattern STATUS_LINE = Pattern.compile("^[A-Z][A-Z ]*( [0-9]+)*$");
    private static final Pattern FOOTER = Pattern.compile("^\\((\\d+) rows?\\)\n");
    private static final Set<String> SYSTEM_DATABASES =
            Set.of("postgres", "template0", "template1");
    private static final String FS = "\u001f";
    private static final String RS = "\u001e";
    private static final String NULL = "\u0001";
    private static final int MAX_ROWS = 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseServerRepository repository;
    private final DockerHostService dockerHostService;
    private final EncryptionService encryptionService;

    public PostgresService(
            DatabaseServerRepository repository,
            DockerHostService dockerHostService,
            EncryptionService encryptionService) {
        this.repository = repository;
        this.dockerHostService = dockerHostService;
        this.encryptionService = encryptionService;
    }

    // --- servers -----------------------------------------------------------------------------

    public record ServerView(
            DatabaseServer server,
            String state,
            String health,
            String version,
            String containerId) {}

    public List<ServerView> list(String hostId) {
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        return repository.findByDockerHostIdOrderByNameAsc(hostId).stream()
                .map(s -> view(api, s))
                .toList();
    }

    public ServerView view(String serverId) {
        DatabaseServer s = require(serverId);
        return view(dockerHostService.getDockerAPI(s.getDockerHostId()), s);
    }

    private ServerView view(DockerAPI api, DatabaseServer s) {
        try {
            InspectContainerResponse c = api.inspectContainer(s.getContainerName());
            var state = c.getState();
            String health = state.getHealth() == null ? null : state.getHealth().getStatus();
            String version =
                    s.getImage().replaceFirst("^postgres:", "").replaceFirst("-alpine$", "");
            return new ServerView(s, state.getStatus(), health, version, c.getId());
        } catch (NotFoundException e) {
            return new ServerView(s, "missing", null, null, null);
        }
    }

    public record CreateRequest(String name, String version, Integer port, String address) {}

    public DatabaseServer create(String hostId, CreateRequest request) {
        String name = request.name() == null ? "" : request.name().trim().toLowerCase(Locale.ROOT);
        if (!SERVER_NAME.matcher(name).matches()) {
            throw bad("Use 3 to 32 lowercase letters, digits or dashes, starting with a letter");
        }
        String version = request.version() == null ? VERSIONS.getFirst() : request.version();
        if (!VERSIONS.contains(version))
            throw bad("Choose PostgreSQL " + String.join(", ", VERSIONS));
        Integer port = request.port();
        String address = "0.0.0.0".equals(request.address()) ? "0.0.0.0" : "127.0.0.1";
        if (port != null && (port < 1024 || port > 65535))
            throw bad("Choose a port between 1024 and 65535");
        if (repository.existsByDockerHostIdAndName(hostId, name)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A server named " + name + " already exists");
        }
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        String container = "dm-pg-" + name;
        if (NginxApplier.findContainer(api, container) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A container named " + container + " already exists");
        }

        DatabaseServer s = new DatabaseServer();
        s.setDockerHostId(hostId);
        s.setName(name);
        s.setImage("postgres:" + version + "-alpine");
        s.setContainerName(container);
        s.setVolumeName("dm-pg-" + name + "-data");
        s.setNetworkName("dm-db-" + name);
        s.setSuperuser("postgres");
        String password = randomPassword();
        s.setSuperuserPassword(encryptionService.encrypt(password));
        s.setPublishedPort(port);
        s.setPublishedAddress(port == null ? null : address);
        s.setCreatedAt(System.currentTimeMillis());
        s.setBackupIntervalHours(24);
        s.setBackupKeep(7);
        s.setLastAutoBackupAt(System.currentTimeMillis());

        ToolRunner.ensureImage(api, s.getImage());
        String dataPath = dataPath(api, s.getImage(), version);
        Map<String, String> labels = Map.of(LABEL, name);
        boolean existingData = false;
        try {
            api.client().inspectVolumeCmd(s.getVolumeName()).exec();
            existingData = true;
        } catch (NotFoundException e) {
            api.client().createVolumeCmd().withName(s.getVolumeName()).withLabels(labels).exec();
        }
        boolean madeNetwork = false;
        try {
            api.client().inspectNetworkCmd().withNetworkId(s.getNetworkName()).exec();
        } catch (NotFoundException e) {
            api.client()
                    .createNetworkCmd()
                    .withName(s.getNetworkName())
                    .withDriver("bridge")
                    .withLabels(labels)
                    .exec();
            madeNetwork = true;
        }

        HostConfig hc =
                HostConfig.newHostConfig()
                        .withBinds(new Bind(s.getVolumeName(), new Volume(dataPath)))
                        .withNetworkMode(s.getNetworkName())
                        .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
                        .withShmSize(256L * 1024 * 1024)
                        .withSecurityOpts(List.of("no-new-privileges:true"));
        var create =
                api.client()
                        .createContainerCmd(s.getImage())
                        .withName(container)
                        .withLabels(labels)
                        .withEnv(
                                "POSTGRES_PASSWORD=" + password,
                                "POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 --auth-local=trust")
                        .withHealthcheck(
                                new HealthCheck()
                                        .withTest(
                                                List.of(
                                                        "CMD-SHELL",
                                                        "pg_isready -U postgres -d postgres"))
                                        .withInterval(TimeUnit.SECONDS.toNanos(10))
                                        .withTimeout(TimeUnit.SECONDS.toNanos(5))
                                        .withRetries(6)
                                        .withStartPeriod(TimeUnit.SECONDS.toNanos(60)));
        if (port != null) {
            Ports ports = new Ports();
            ports.bind(ExposedPort.tcp(5432), Ports.Binding.bindIpAndPort(address, port));
            hc.withPortBindings(ports);
            create.withExposedPorts(ExposedPort.tcp(5432));
        }
        String id = null;
        try {
            id = create.withHostConfig(hc).exec().getId();
            api.startContainer(id);
            waitReady(api, s);
            if (existingData) {
                // Old data keeps its old password; make it the one shown here.
                psqlOrThrow(
                        api,
                        s,
                        "postgres",
                        "ALTER ROLE postgres PASSWORD '" + scramVerifier(password) + "'");
            }
            // New databases are copied from template1: nobody but their owner may create objects.
            psqlOrThrow(api, s, "template1", "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            // The built-in database is for administration; app users get their own.
            psqlOrThrow(
                    api,
                    s,
                    "postgres",
                    "REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC; REVOKE CREATE ON"
                            + " SCHEMA public FROM PUBLIC");
            return repository.save(s);
        } catch (RuntimeException e) {
            if (id != null) {
                try {
                    api.client().removeContainerCmd(id).withForce(true).exec();
                } catch (Exception ignored) {
                }
            }
            if (madeNetwork) {
                try {
                    api.client().removeNetworkCmd(s.getNetworkName()).exec();
                } catch (Exception ignored) {
                }
            }
            if (!existingData) {
                try {
                    api.removeVolume(s.getVolumeName());
                } catch (Exception ignored) {
                }
            }
            throw e;
        }
    }

    /** The image's own data volume path: /var/lib/postgresql for 18, .../data before that. */
    private static String dataPath(DockerAPI api, String image, String version) {
        try {
            var volumes = api.client().inspectImageCmd(image).exec().getConfig().getVolumes();
            if (volumes != null) {
                for (String path : volumes.keySet()) {
                    if (path.startsWith("/var/lib/postgresql")) return path;
                }
            }
        } catch (Exception e) {
            log.debug("Could not read the volumes of {}: {}", image, e.getMessage());
        }
        return Integer.parseInt(version) >= 18 ? "/var/lib/postgresql" : "/var/lib/postgresql/data";
    }

    private void waitReady(DockerAPI api, DatabaseServer s) {
        long deadline = System.currentTimeMillis() + 120_000;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            var out =
                    api.execFull(
                            s.getContainerName(),
                            Map.of(),
                            15,
                            "psql",
                            "-U",
                            s.getSuperuser(),
                            "-X",
                            "-d",
                            "postgres",
                            "-Atc",
                            "select 1");
            if (out.ok()) return;
            last = out.stderr();
            var state = api.inspectContainer(s.getContainerName()).getState();
            if (!Boolean.TRUE.equals(state.getRunning())) {
                throw new IllegalStateException(
                        "PostgreSQL stopped while starting (exit code "
                                + state.getExitCodeLong()
                                + ")");
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted");
            }
        }
        throw new IllegalStateException(
                "PostgreSQL didn't start within 2 minutes: " + last.strip());
    }

    /** Removes the server. Its data volume and backups stay unless asked otherwise. */
    public void remove(String serverId, boolean deleteData) {
        DatabaseServer s = require(serverId);
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        String id = NginxApplier.findContainer(api, s.getContainerName());
        if (id != null) api.client().removeContainerCmd(id).withForce(true).exec();
        try {
            var network = api.client().inspectNetworkCmd().withNetworkId(s.getNetworkName()).exec();
            if (network.getContainers() != null) {
                for (String member : network.getContainers().keySet()) {
                    api.client()
                            .disconnectFromNetworkCmd()
                            .withNetworkId(network.getId())
                            .withContainerId(member)
                            .withForce(true)
                            .exec();
                }
            }
            api.client().removeNetworkCmd(network.getId()).exec();
        } catch (NotFoundException ignored) {
        } catch (Exception e) {
            log.warn("Could not remove network {}: {}", s.getNetworkName(), e.getMessage());
        }
        if (deleteData) {
            try {
                api.removeVolume(s.getVolumeName());
            } catch (NotFoundException ignored) {
            }
        }
        repository.delete(s);
    }

    /** Hides the outcome of the last restore or import once it has been read. */
    public void clearResult(String serverId) {
        DatabaseServer s = require(serverId);
        s.setLastResult(null);
        s.setLastResultFailed(false);
        repository.save(s);
    }

    public String superuserPassword(String serverId) {
        return encryptionService.decrypt(require(serverId).getSuperuserPassword());
    }

    // --- SQL console -------------------------------------------------------------------------

    public record ResultSet(
            List<String> columns, List<List<String>> rows, int rowCount, boolean rowsCut) {}

    public record QueryResult(
            List<ResultSet> results,
            List<String> messages,
            String error,
            boolean outputCut,
            long millis) {}

    public QueryResult query(String serverId, String database, String sql, boolean readOnly) {
        DatabaseServer s = require(serverId);
        ident(database, "database");
        if (sql == null || sql.isBlank()) throw bad("Enter a query");
        if (sql.strip().startsWith("\\"))
            throw bad("psql commands (starting with \\) aren't supported here");
        if (sql.length() > 100_000) throw bad("The query is too long");
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        String options =
                "-c statement_timeout=300000"
                        + (readOnly ? " -c default_transaction_read_only=on" : "");
        int max = 8 * 1024 * 1024;
        long started = System.currentTimeMillis();
        var out =
                api.execFull(
                        s.getContainerName(),
                        Map.of("PGOPTIONS", options),
                        320,
                        max,
                        "psql",
                        "-U",
                        s.getSuperuser(),
                        "-X",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-d",
                        database,
                        "-A",
                        "-F",
                        FS,
                        "-R",
                        RS,
                        "-P",
                        "footer=on",
                        "-P",
                        "null=" + NULL,
                        "-c",
                        sql);
        long millis = System.currentTimeMillis() - started;
        String text = out.stdoutText();
        boolean cut = out.stdout().length >= max;
        List<ResultSet> results = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        parse(text, results, messages);
        for (String line : out.stderr().split("\n")) {
            if (line.startsWith("NOTICE:")
                    || line.startsWith("WARNING:")
                    || line.startsWith("INFO:")) messages.add(line.strip());
        }
        String error = null;
        if (!out.ok()) {
            StringBuilder sb = new StringBuilder();
            for (String line : out.stderr().split("\n")) {
                if (line.startsWith("NOTICE:")
                        || line.startsWith("WARNING:")
                        || line.startsWith("INFO:")) continue;
                if (!line.isBlank()) sb.append(line).append('\n');
            }
            error = sb.toString().strip();
            if (error.isEmpty()) error = "psql exited with code " + out.exitCode();
        }
        return new QueryResult(results, messages, error, cut, millis);
    }

    /** Splits psql's unaligned output (fields \x1f, records \x1e, "(n rows)" footers). */
    static void parse(String text, List<ResultSet> results, List<String> messages) {
        int pos = 0;
        int n = text.length();
        while (pos < n) {
            int nl = text.indexOf('\n', pos);
            int rs = text.indexOf(RS, pos);
            if (rs < 0 || (nl >= 0 && nl < rs)) {
                // A status line such as "INSERT 0 1", or a header of a result with no columns.
                String line = text.substring(pos, nl < 0 ? n : nl);
                if (!line.isBlank()) messages.add(line);
                pos = nl < 0 ? n : nl + 1;
                continue;
            }
            List<String> columns = List.of(text.substring(pos, rs).split(FS, -1));
            pos = rs + 1;
            List<List<String>> rows = new ArrayList<>();
            int count = 0;
            boolean complete = false;
            while (pos < n) {
                var m = FOOTER.matcher(text.substring(pos, Math.min(n, pos + 40)));
                if (m.lookingAt()) {
                    pos += m.end();
                    complete = true;
                    break;
                }
                int end = text.indexOf(RS, pos);
                String record = text.substring(pos, end < 0 ? n : end);
                pos = end < 0 ? n : end + 1;
                count++;
                if (rows.size() < MAX_ROWS) {
                    List<String> row = new ArrayList<>();
                    for (String v : record.split(FS, -1)) row.add(NULL.equals(v) ? null : v);
                    rows.add(row);
                }
            }
            results.add(new ResultSet(columns, rows, count, count > rows.size() || !complete));
        }
    }

    // --- databases ---------------------------------------------------------------------------

    public record DatabaseInfo(
            String name, String owner, long sizeBytes, int connections, List<Access> access) {}

    public record Access(String role, String level) {}

    public List<DatabaseInfo> databases(String serverId) {
        DatabaseServer s = require(serverId);
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        List<String[]> rows =
                rows(
                        api,
                        s,
                        "postgres",
                        "select d.datname, pg_get_userbyid(d.datdba), pg_database_size(d.oid),"
                            + " (select count(*) from pg_stat_activity a where a.datname ="
                            + " d.datname) from pg_database d where not d.datistemplate order by"
                            + " d.datname = 'postgres', d.datname");
        Map<String, List<Access>> access = access(api, s, rows.stream().map(r -> r[0]).toList());
        List<DatabaseInfo> list = new ArrayList<>();
        for (String[] r : rows) {
            list.add(
                    new DatabaseInfo(
                            r[0],
                            r[1],
                            Long.parseLong(r[2]),
                            Integer.parseInt(r[3]),
                            access.getOrDefault(r[0], List.of())));
        }
        return list;
    }

    /** Who may connect to each database, and whether they can write (create in public). */
    private Map<String, List<Access>> access(
            DockerAPI api, DatabaseServer s, List<String> databases) {
        Map<String, List<Access>> result = new HashMap<>();
        List<String> connect = new ArrayList<>();
        for (String[] r :
                rows(
                        api,
                        s,
                        "postgres",
                        "select d.datname, coalesce(r.rolname, 'PUBLIC') from pg_database d cross"
                            + " join lateral aclexplode(coalesce(d.datacl, acldefault('d',"
                            + " d.datdba))) a left join pg_roles r on r.oid = a.grantee where"
                            + " a.privilege_type = 'CONNECT' and not d.datistemplate and (r.oid is"
                            + " null or (not r.rolsuper and r.oid <> d.datdba))")) {
            connect.add(r[0] + FS + r[1]);
        }
        for (String db : databases) {
            List<Access> list = new ArrayList<>();
            Set<String> writers = new HashSet<>();
            boolean hasOthers =
                    connect.stream()
                            .anyMatch(c -> c.startsWith(db + FS) && !c.endsWith(FS + "PUBLIC"));
            if (hasOthers) {
                try {
                    for (String[] w :
                            rows(
                                    api,
                                    s,
                                    db,
                                    "select r.rolname from pg_roles r, pg_namespace n where"
                                            + " n.nspname = 'public' and not r.rolsuper and"
                                            + " has_schema_privilege(r.oid, n.oid, 'CREATE')")) {
                        writers.add(w[0]);
                    }
                } catch (RuntimeException e) {
                    log.debug("Could not read privileges in {}: {}", db, e.getMessage());
                }
            }
            for (String c : connect) {
                if (!c.startsWith(db + FS)) continue;
                String role = c.substring(db.length() + 1);
                list.add(
                        new Access(
                                role,
                                "PUBLIC".equals(role)
                                        ? "PUBLIC"
                                        : writers.contains(role) ? "WRITE" : "READ"));
            }
            result.put(db, list);
        }
        return result;
    }

    public record Created(String name, String user, String password, String url) {}

    /**
     * Creates a database. With {@code newUser}, also creates a login of the same name that owns it
     * and returns that user's password (shown once). The database is closed to everyone else.
     */
    public Created createDatabase(String serverId, String name, String owner, boolean newUser) {
        DatabaseServer s = require(serverId);
        ident(name, "database");
        if (SYSTEM_DATABASES.contains(name)) throw bad("That name is reserved");
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        if (!rows(api, s, "postgres", "select 1 from pg_database where datname = " + literal(name))
                .isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A database named " + name + " already exists");
        }
        String password = null;
        String user = owner == null || owner.isBlank() ? name : owner.trim();
        ident(user, "user");
        if (newUser) {
            if (roleExists(api, s, user)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "A user named " + user + " already exists");
            }
            password = randomPassword();
            psqlOrThrow(
                    api,
                    s,
                    "postgres",
                    "CREATE ROLE "
                            + quote(user)
                            + " LOGIN PASSWORD '"
                            + scramVerifier(password)
                            + "'");
        } else if (!roleExists(api, s, user)) {
            throw bad("There's no user named " + user);
        }
        try {
            psqlOrThrow(
                    api, s, "postgres", "CREATE DATABASE " + quote(name) + " OWNER " + quote(user));
        } catch (RuntimeException e) {
            if (newUser) psql(api, s, "postgres", "DROP ROLE IF EXISTS " + quote(user));
            throw e;
        }
        psqlOrThrow(
                api,
                s,
                "postgres",
                "REVOKE CONNECT, TEMPORARY ON DATABASE " + quote(name) + " FROM PUBLIC");
        return new Created(name, user, password, url(s, user, password, name));
    }

    public void dropDatabase(String serverId, String name) {
        DatabaseServer s = require(serverId);
        ident(name, "database");
        if (SYSTEM_DATABASES.contains(name)) throw bad("That database can't be removed");
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        psqlOrThrow(api, s, "postgres", "DROP DATABASE IF EXISTS " + quote(name) + " WITH (FORCE)");
    }

    // --- users -------------------------------------------------------------------------------

    public record Role(
            String name, boolean login, boolean superuser, boolean createDb, List<String> owns) {}

    public List<Role> roles(String serverId) {
        DatabaseServer s = require(serverId);
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        List<Role> list = new ArrayList<>();
        for (String[] r :
                rows(
                        api,
                        s,
                        "postgres",
                        "select r.rolname, r.rolcanlogin, r.rolsuper, r.rolcreatedb,"
                            + " coalesce((select string_agg(d.datname, ',' order by d.datname) from"
                            + " pg_database d where d.datdba = r.oid and not d.datistemplate), '')"
                            + " from pg_roles r where r.rolname !~ '^pg_' order by r.rolsuper desc,"
                            + " r.rolname")) {
            list.add(
                    new Role(
                            r[0],
                            "t".equals(r[1]),
                            "t".equals(r[2]),
                            "t".equals(r[3]),
                            r[4].isEmpty() ? List.of() : List.of(r[4].split(","))));
        }
        return list;
    }

    /** Creates a login user. Returns its password (generated when none is given). */
    public String createRole(String serverId, String name, String password) {
        DatabaseServer s = require(serverId);
        ident(name, "user");
        String pw =
                password == null || password.isEmpty() ? randomPassword() : checkPassword(password);
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        if (roleExists(api, s, name))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That user already exists");
        psqlOrThrow(
                api,
                s,
                "postgres",
                "CREATE ROLE " + quote(name) + " LOGIN PASSWORD '" + scramVerifier(pw) + "'");
        return pw;
    }

    public String setPassword(String serverId, String name, String password) {
        DatabaseServer s = require(serverId);
        ident(name, "user");
        String pw =
                password == null || password.isEmpty() ? randomPassword() : checkPassword(password);
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        if (!roleExists(api, s, name))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user");
        psqlOrThrow(
                api,
                s,
                "postgres",
                "ALTER ROLE " + quote(name) + " PASSWORD '" + scramVerifier(pw) + "'");
        if (name.equals(s.getSuperuser())) {
            s.setSuperuserPassword(encryptionService.encrypt(pw));
            repository.save(s);
        }
        return pw;
    }

    public void dropRole(String serverId, String name) {
        DatabaseServer s = require(serverId);
        ident(name, "user");
        if (name.equals(s.getSuperuser())) throw bad("That's the server's own administrator");
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        if (!roleExists(api, s, name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user");
        }
        // Never delete data with a user: refuse while it owns a database or anything in one.
        // (pg_shdepend records ownership in every database of the server.)
        String roleOid = "(select oid from pg_roles where rolname = " + literal(name) + ")";
        List<String[]> owned =
                rows(
                        api,
                        s,
                        "postgres",
                        "select d.datname, count(*) from pg_shdepend sd join pg_database d on"
                                + " d.oid = sd.dbid where sd.refclassid = 'pg_authid'::regclass"
                                + " and sd.refobjid = "
                                + roleOid
                                + " and sd.deptype = 'o' group by d.datname order by 1");
        List<String[]> databases =
                rows(
                        api,
                        s,
                        "postgres",
                        "select datname from pg_database where datdba = "
                                + roleOid
                                + " order by 1");
        if (!databases.isEmpty() || !owned.isEmpty()) {
            List<String> where = new ArrayList<>();
            databases.forEach(d -> where.add("the database " + d[0]));
            owned.forEach(
                    o ->
                            where.add(
                                    o[1]
                                            + ("1".equals(o[1]) ? " object" : " objects")
                                            + " (tables and such) in "
                                            + o[0]));
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    name
                            + " owns "
                            + String.join(", ", where)
                            + ". Delete those or give them to another user first; deleting the"
                            + " user would delete them too.");
        }
        // Only privileges are left. DROP OWNED removes them; the check runs again in the same
        // transaction, so something created in the meantime is never dropped.
        for (String[] db :
                rows(api, s, "postgres", "select datname from pg_database where datallowconn")) {
            String sql =
                    "DO $dm$ BEGIN IF EXISTS (select 1 from pg_shdepend where refclassid ="
                            + " 'pg_authid'::regclass and refobjid = "
                            + roleOid
                            + " and deptype = 'o' and dbid = (select oid from pg_database where"
                            + " datname = current_database())) THEN RAISE EXCEPTION '% owns"
                            + " objects in %', "
                            + literal(name)
                            + ", current_database(); END IF; END $dm$; REVOKE ALL ON DATABASE "
                            + quote(db[0])
                            + " FROM "
                            + quote(name)
                            + "; DROP OWNED BY "
                            + quote(name);
            var out = psql(api, s, db[0], sql);
            if (!out.ok()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, clean(out.stderr()));
            }
        }
        var out = psql(api, s, "postgres", "DROP ROLE " + quote(name));
        if (!out.ok()) {
            String err = clean(out.stderr());
            if (err.contains("owner of database")) {
                err =
                        name
                                + " owns a database. Remove the database first, or give it to"
                                + " another user.";
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, err);
        }
    }

    /** Gives a user no access, read-only access, or read and write access to a database. */
    public void setAccess(String serverId, String database, String role, String level) {
        DatabaseServer s = require(serverId);
        ident(database, "database");
        ident(role, "user");
        if (SYSTEM_DATABASES.contains(database)) throw bad("Choose one of your databases");
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        List<String[]> info =
                rows(
                        api,
                        s,
                        "postgres",
                        "select pg_get_userbyid(datdba) from pg_database where datname = "
                                + literal(database));
        if (info.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such database");
        String owner = info.getFirst()[0];
        if (owner.equals(role)) throw bad(role + " owns this database and always has full access");
        if (!roleExists(api, s, role))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user");
        List<String> schemas = new ArrayList<>();
        for (String[] r :
                rows(
                        api,
                        s,
                        database,
                        "select nspname from pg_namespace where nspname !~ '^pg_' and nspname <>"
                                + " 'information_schema'")) {
            schemas.add(r[0]);
        }
        String r = quote(role);
        StringBuilder sql = new StringBuilder();
        String lvl = level == null ? "" : level.toUpperCase(Locale.ROOT);
        switch (lvl) {
            case "NONE" -> {
                sql.append("REVOKE ALL ON DATABASE ")
                        .append(quote(database))
                        .append(" FROM ")
                        .append(r)
                        .append(";");
                for (String schema : schemas) {
                    String sc = quote(schema);
                    sql.append("REVOKE ALL ON ALL TABLES IN SCHEMA ")
                            .append(sc)
                            .append(" FROM ")
                            .append(r)
                            .append(";")
                            .append("REVOKE ALL ON ALL SEQUENCES IN SCHEMA ")
                            .append(sc)
                            .append(" FROM ")
                            .append(r)
                            .append(";")
                            .append("REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ")
                            .append(sc)
                            .append(" FROM ")
                            .append(r)
                            .append(";")
                            .append("REVOKE ALL ON SCHEMA ")
                            .append(sc)
                            .append(" FROM ")
                            .append(r)
                            .append(";")
                            .append("ALTER DEFAULT PRIVILEGES FOR ROLE ")
                            .append(quote(owner))
                            .append(" IN SCHEMA ")
                            .append(sc)
                            .append(" REVOKE ALL ON TABLES FROM ")
                            .append(r)
                            .append(";")
                            .append("ALTER DEFAULT PRIVILEGES FOR ROLE ")
                            .append(quote(owner))
                            .append(" IN SCHEMA ")
                            .append(sc)
                            .append(" REVOKE ALL ON SEQUENCES FROM ")
                            .append(r)
                            .append(";");
                }
            }
            case "READ", "WRITE" -> {
                boolean write = lvl.equals("WRITE");
                sql.append("GRANT CONNECT ON DATABASE ")
                        .append(quote(database))
                        .append(" TO ")
                        .append(r)
                        .append(";");
                if (!write) {
                    // Going from write to read: take the extra rights away first.
                    sql.append("REVOKE TEMPORARY ON DATABASE ")
                            .append(quote(database))
                            .append(" FROM ")
                            .append(r)
                            .append(";");
                }
                for (String schema : schemas) {
                    String sc = quote(schema);
                    String adp =
                            "ALTER DEFAULT PRIVILEGES FOR ROLE "
                                    + quote(owner)
                                    + " IN SCHEMA "
                                    + sc;
                    if (write) {
                        sql.append("GRANT USAGE, CREATE ON SCHEMA ")
                                .append(sc)
                                .append(" TO ")
                                .append(r)
                                .append(";")
                                .append("GRANT ALL ON ALL TABLES IN SCHEMA ")
                                .append(sc)
                                .append(" TO ")
                                .append(r)
                                .append(";")
                                .append("GRANT ALL ON ALL SEQUENCES IN SCHEMA ")
                                .append(sc)
                                .append(" TO ")
                                .append(r)
                                .append(";")
                                .append("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA ")
                                .append(sc)
                                .append(" TO ")
                                .append(r)
                                .append(";")
                                .append(adp)
                                .append(" GRANT ALL ON TABLES TO ")
                                .append(r)
                                .append(";")
                                .append(adp)
                                .append(" GRANT ALL ON SEQUENCES TO ")
                                .append(r)
                                .append(";");
                    } else {
                        sql.append("REVOKE ALL ON ALL TABLES IN SCHEMA ")
                                .append(sc)
                                .append(" FROM ")
                                .append(r)
                                .append(";")
                                .append("REVOKE ALL ON ALL SEQUENCES IN SCHEMA ")
                                .append(sc)
                                .append(" FROM ")
                                .append(r)
                                .append(";")
                                .append("REVOKE CREATE ON SCHEMA ")
                                .append(sc)
                                .append(" FROM ")
                                .append(r)
                                .append(";")
                                .append(adp)
                                .append(" REVOKE ALL ON TABLES FROM ")
                                .append(r)
                                .append(";")
                                .append(adp)
                                .append(" REVOKE ALL ON SEQUENCES FROM ")
                                .append(r)
                                .append(";")
                                .append("GRANT USAGE ON SCHEMA ")
                                .append(sc)
                                .append(" TO ")
                                .append(r)
                                .append(";")
                                .append("GRANT SELECT ON ALL TABLES IN SCHEMA ")
                                .append(sc)
                                .append(" TO ")
                                .append(r)
                                .append(";")
                                .append("GRANT SELECT ON ALL SEQUENCES IN SCHEMA ")
                                .append(sc)
                                .append(" TO ")
                                .append(r)
                                .append(";")
                                .append(adp)
                                .append(" GRANT SELECT ON TABLES TO ")
                                .append(r)
                                .append(";")
                                .append(adp)
                                .append(" GRANT SELECT ON SEQUENCES TO ")
                                .append(r)
                                .append(";");
                    }
                }
            }
            default -> throw bad("Choose none, read or write");
        }
        // One -c string runs as one transaction: all of it applies, or none.
        psqlOrThrow(api, s, database, sql.toString());
    }

    // --- containers that use it --------------------------------------------------------------

    public record Member(String id, String name) {}

    public List<Member> members(String serverId) {
        DatabaseServer s = require(serverId);
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        List<Member> list = new ArrayList<>();
        try {
            var network = api.client().inspectNetworkCmd().withNetworkId(s.getNetworkName()).exec();
            if (network.getContainers() != null) {
                network.getContainers()
                        .forEach(
                                (id, c) -> {
                                    String n =
                                            c.getName() == null
                                                    ? id
                                                    : c.getName().replaceFirst("^/", "");
                                    if (!n.equals(s.getContainerName()))
                                        list.add(new Member(id, n));
                                });
            }
        } catch (NotFoundException ignored) {
        }
        list.sort(Comparator.comparing(Member::name));
        return list;
    }

    public void connect(String serverId, String container) {
        DatabaseServer s = require(serverId);
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        InspectContainerResponse c = api.inspectContainer(container);
        Map<String, ContainerNetwork> nets = c.getNetworkSettings().getNetworks();
        if (nets != null && nets.containsKey(s.getNetworkName())) return;
        api.client()
                .connectToNetworkCmd()
                .withNetworkId(s.getNetworkName())
                .withContainerId(c.getId())
                .exec();
    }

    public void disconnect(String serverId, String container) {
        DatabaseServer s = require(serverId);
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        if (container.equals(s.getContainerName())) throw bad("That's the server itself");
        api.client()
                .disconnectFromNetworkCmd()
                .withNetworkId(s.getNetworkName())
                .withContainerId(container)
                .exec();
    }

    public static String url(DatabaseServer s, String user, String password, String database) {
        return "postgresql://"
                + user
                + ":"
                + (password == null ? "PASSWORD" : password)
                + "@"
                + s.getContainerName()
                + ":5432/"
                + database;
    }

    // --- psql plumbing (also used by backups) ------------------------------------------------

    DatabaseServer require(String serverId) {
        return repository
                .findById(serverId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "No such database server"));
    }

    DockerAPI api(DatabaseServer s) {
        return dockerHostService.getDockerAPI(s.getDockerHostId());
    }

    DockerAPI.ExecOutput psql(DockerAPI api, DatabaseServer s, String database, String sql) {
        return api.execFull(
                s.getContainerName(),
                Map.of(),
                600,
                "psql",
                "-U",
                s.getSuperuser(),
                "-X",
                "-q",
                "-v",
                "ON_ERROR_STOP=1",
                "-d",
                database,
                "-c",
                sql);
    }

    void psqlOrThrow(DockerAPI api, DatabaseServer s, String database, String sql) {
        var out = psql(api, s, database, sql);
        if (!out.ok())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, clean(out.stderr()));
    }

    /** Runs a query and returns its rows (fields split; no header). */
    List<String[]> rows(DockerAPI api, DatabaseServer s, String database, String sql) {
        var out =
                api.execFull(
                        s.getContainerName(),
                        Map.of(),
                        120,
                        "psql",
                        "-U",
                        s.getSuperuser(),
                        "-X",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-d",
                        database,
                        "-A",
                        "-t",
                        "-F",
                        FS,
                        "-R",
                        RS,
                        "-c",
                        sql);
        if (!out.ok())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, clean(out.stderr()));
        String text = out.stdoutText();
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        List<String[]> rows = new ArrayList<>();
        if (text.isEmpty()) return rows;
        for (String record : text.split(RS, -1)) rows.add(record.split(FS, -1));
        return rows;
    }

    boolean roleExists(DockerAPI api, DatabaseServer s, String role) {
        return !rows(api, s, "postgres", "select 1 from pg_roles where rolname = " + literal(role))
                .isEmpty();
    }

    /** psql's error output without the "psql:" prefixes and caret lines. */
    static String clean(String stderr) {
        StringBuilder sb = new StringBuilder();
        for (String line : stderr.split("\n")) {
            String l =
                    line.replaceFirst("^psql:(<stdin>|[^:]*):\\d+: ", "")
                            .replaceFirst("^psql: error: ", "");
            if (l.isBlank() || l.startsWith("LINE ") || l.strip().equals("^")) continue;
            sb.append(l.strip()).append('\n');
        }
        String s = sb.toString().strip();
        return s.isEmpty() ? "PostgreSQL reported an error" : s;
    }

    static void ident(String value, String what) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw bad(
                    "A "
                            + what
                            + " name uses lowercase letters, digits and _, starting with a letter"
                            + " or _");
        }
    }

    static String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String checkPassword(String password) {
        if (password.length() < 12) throw bad("Use a password of at least 12 characters");
        for (char c : password.toCharArray()) {
            if (c < 0x21 || c > 0x7e)
                throw bad("Use letters, digits and symbols (no spaces or accents) in passwords");
        }
        return password;
    }

    static String randomPassword() {
        return RegistryHostingService.randomPassword();
    }

    /**
     * The SCRAM-SHA-256 verifier PostgreSQL stores for a password (RFC 5802/7677, 4096 iterations,
     * 16-byte salt), so the password itself is never sent in SQL.
     */
    static String scramVerifier(String password) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            int iterations = 4096;
            byte[] pw = password.getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pw, "HmacSHA256"));
            mac.update(salt);
            byte[] u = mac.doFinal(new byte[] {0, 0, 0, 1});
            byte[] salted = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < salted.length; j++) salted[j] ^= u[j];
            }
            Mac keyed = Mac.getInstance("HmacSHA256");
            keyed.init(new SecretKeySpec(salted, "HmacSHA256"));
            byte[] clientKey = keyed.doFinal("Client Key".getBytes(StandardCharsets.UTF_8));
            byte[] serverKey = keyed.doFinal("Server Key".getBytes(StandardCharsets.UTF_8));
            byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
            Base64.Encoder b64 = Base64.getEncoder();
            return "SCRAM-SHA-256$"
                    + iterations
                    + ":"
                    + b64.encodeToString(salt)
                    + "$"
                    + b64.encodeToString(storedKey)
                    + ":"
                    + b64.encodeToString(serverKey);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
