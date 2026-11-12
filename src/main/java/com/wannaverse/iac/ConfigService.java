package com.wannaverse.iac;

import com.wannaverse.persistence.ConfigState;
import com.wannaverse.persistence.ConfigStateRepository;
import com.wannaverse.security.SecurityContext;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.AuditService;
import com.wannaverse.service.EncryptionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Configuration as code: reads a YAML file describing Docker Manager's setup and applies it.
 *
 * <p>The file comes either from {@code DM_CONFIG_FILE} (read at startup, whenever it changes, and
 * checked again every few minutes) or from an administrator on the Configuration page. Only one
 * apply runs at a time.
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    public static final String SOURCE_FILE = "FILE";
    public static final String SOURCE_UI = "UI";
    public static final String FILE_ACTOR = "config file";
    public static final String SCHEDULED_ACTOR = "scheduled check";

    private final ConfigEngine engine;
    private final ConfigParser parser;
    private final ConfigStateRepository states;
    private final EncryptionService encryption;
    private final AuditService audit;
    private final Path file;
    private final Path dataDir;

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "config-apply");
                        t.setDaemon(true);
                        return t;
                    });
    private static final com.fasterxml.jackson.databind.ObjectMapper PLAIN_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final Deque<ConfigRun> runs = new ArrayDeque<>();
    private volatile ConfigRun current;

    public ConfigService(
            ConfigEngine engine,
            ConfigStateRepository states,
            EncryptionService encryption,
            AuditService audit,
            @Value("${app.config.file:}") String file,
            @Value("${app.data-dir:database}") String dataDir) {
        this.engine = engine;
        this.parser = new ConfigParser(engine.handlers());
        this.states = states;
        this.encryption = encryption;
        this.audit = audit;
        this.file = file == null || file.isBlank() ? null : Path.of(file.trim());
        this.dataDir = Path.of(dataDir);
        Secret.setHashKey(encryption.deriveKey("config-secret-fingerprint"));
    }

    /** Whether the configuration comes from DM_CONFIG_FILE (the page is then read-only). */
    public boolean fileMode() {
        return file != null;
    }

    public Path file() {
        return file;
    }

    public java.util.List<ResourceHandler<?>> handlers() {
        return engine.handlers();
    }

    private Interpolator interpolator() {
        Path base = file == null ? null : file.toAbsolutePath().getParent();
        return Interpolator.system(base, dataDir);
    }

    public ConfigDocument parse(String yaml) {
        return parser.parse(yaml, interpolator());
    }

    /** What applying this text would change. */
    public Plan plan(String yaml, String actor) {
        return engine.prepare(parse(yaml), actor).plan();
    }

    /** Reads DM_CONFIG_FILE. Empty when it is not set or can't be read. */
    public Optional<String> readFile() {
        if (file == null) return Optional.empty();
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Could not read the configuration file {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** The document last applied, as written. */
    public Optional<String> storedDocument() {
        return states.findById(ConfigState.ID)
                .map(ConfigState::getEncryptedDocument)
                .filter(s -> s != null && !s.isBlank())
                .map(encryption::decrypt);
    }

    public Optional<ConfigState> state() {
        return states.findById(ConfigState.ID);
    }

    /** An administrator applies a document from the Configuration page or the API. */
    public ConfigRun applyFromUi(String yaml, SecurityContext who) {
        if (fileMode()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Docker Manager reads its configuration from "
                            + file
                            + ". Change that file instead.");
        }
        return start(yaml, SOURCE_UI, who.getUserId(), who.getUsername(), true);
    }

    /**
     * Applies the file (or, without one, the document last applied from the page) if anything
     * differs from it. Used when the file changes and by the periodic check.
     *
     * @param always record a run even when nothing changed (an administrator asked for it)
     */
    public ConfigRun reconcile(boolean always, SecurityContext who) {
        String yaml;
        String source;
        if (fileMode()) {
            Optional<String> text = readFile();
            if (text.isEmpty()) {
                if (always) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Could not read the configuration file " + file);
                }
                return null;
            }
            yaml = text.get();
            source = SOURCE_FILE;
        } else {
            var stored = state().filter(s -> SOURCE_UI.equals(s.getSource()));
            if (stored.isEmpty()) {
                if (always) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "No configuration has been applied yet");
                }
                return null;
            }
            yaml = encryption.decrypt(stored.get().getEncryptedDocument());
            source = SOURCE_UI;
        }
        String userId = who == null ? null : who.getUserId();
        String name = who == null ? (fileMode() ? FILE_ACTOR : SCHEDULED_ACTOR) : who.getUsername();
        return start(yaml, source, userId, name, always, who == null && !always);
    }

    public ConfigRun start(
            String yaml, String source, String userId, String actor, boolean always) {
        return start(yaml, source, userId, actor, always, false);
    }

    /**
     * @param periodic the background check: changes that already failed for this same document are
     *     not tried again (a container change that fails would otherwise stop and restore the
     *     container every few minutes). Missing things are still re-created.
     */
    private synchronized ConfigRun start(
            String yaml,
            String source,
            String userId,
            String actor,
            boolean always,
            boolean periodic) {
        ConfigRun running = current;
        if (running != null && running.status == ConfigRun.Status.RUNNING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The configuration is being applied already. Wait for it.");
        }
        ConfigDocument doc = parse(yaml);
        // Planning may run on a scheduler thread (file changes, periodic checks): give it the same
        // identity the apply will have, since some services read the current user.
        boolean ownContext = SecurityContextHolder.getContext() == null;
        if (ownContext) SecurityContextHolder.setContext(identity(userId, actor));
        ConfigEngine.Prepared prepared;
        try {
            prepared = engine.prepare(doc, actor);
        } finally {
            if (ownContext) SecurityContextHolder.clearContext();
        }
        if (periodic && doc.hash().equals(failedHash) && !failedKeys.isEmpty()) {
            prepared = engine.withoutRetries(prepared, failedKeys);
        }
        Plan plan = prepared.plan();
        ConfigRun run = new ConfigRun(source, actor);
        run.problems.addAll(plan.problems());
        run.warnings.addAll(plan.warnings());

        if (!plan.problems().isEmpty()) {
            run.finish(ConfigRun.Status.FAILED);
            remember(run);
            if (always || !doc.hash().equals(lastFailedHash)) {
                lastFailedHash = doc.hash();
                log.warn(
                        "The configuration was not applied: {}",
                        String.join("; ", plan.problems()));
                saveState(yaml, doc.hash(), source, actor, run);
            }
            return run;
        }
        lastFailedHash = null;
        if (!plan.hasChanges() && !always) {
            // Nothing to do; keep the stored copy current (a comment may have changed).
            ConfigState st = states.findById(ConfigState.ID).orElse(null);
            if (st == null || !doc.hash().equals(st.getDocumentHash())) {
                run.finish(ConfigRun.Status.SUCCEEDED);
                saveState(yaml, doc.hash(), source, actor, run);
            }
            return null;
        }

        saveState(yaml, doc.hash(), source, actor, run);
        current = run;
        remember(run);
        ConfigEngine.Prepared toRun = prepared;
        worker.submit(() -> execute(toRun, run, yaml, doc.hash(), userId));
        return run;
    }

    private volatile String lastFailedHash;

    /** Entries that failed in the last apply, and the document they failed with. */
    private volatile Set<String> failedKeys = Set.of();

    private volatile String failedHash;

    private void execute(
            ConfigEngine.Prepared prepared,
            ConfigRun run,
            String yaml,
            String hash,
            String userId) {
        SecurityContextHolder.setContext(identity(userId, run.startedBy));
        try {
            engine.execute(prepared, run);
            failedKeys =
                    run.items.stream()
                            .filter(i -> i.status == ConfigRun.ItemStatus.FAILED)
                            .map(i -> ConfigEngine.itemKey(i.section, i.key))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            failedHash = hash;
        } catch (RuntimeException e) {
            log.error("Applying the configuration failed", e);
            run.problems.add(ConfigEngine.message(e));
            run.finish(ConfigRun.Status.FAILED);
        } finally {
            try {
                saveState(yaml, hash, run.source, run.startedBy, run);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("source", run.source);
                details.put("status", run.status.name());
                details.put(
                        "changes",
                        run.items.stream()
                                .filter(i -> i.action != Plan.Action.UNCHANGED)
                                .map(i -> i.action + " " + i.section + " " + i.key + " " + i.status)
                                .toList());
                audit.logAction(
                        "apply_config",
                        com.wannaverse.persistence.Resource.CONFIGURATION,
                        run.id,
                        details,
                        run.status != ConfigRun.Status.FAILED,
                        run.status == ConfigRun.Status.SUCCEEDED ? null : "Some changes failed");
            } catch (RuntimeException e) {
                log.warn("Could not record the configuration apply", e);
            }
            SecurityContextHolder.clearContext();
        }
    }

    /** Who an apply runs as: the administrator who started it, or "config file" (no user id). */
    private static SecurityContext identity(String userId, String actor) {
        return SecurityContext.builder()
                .userId(userId)
                .username(actor)
                .admin(true)
                .mustChangePassword(false)
                .build();
    }

    /** Waits for a run (used at startup, so later startup steps see its result). */
    public void await(ConfigRun run, long minutes) {
        if (run == null) return;
        Future<?> f = worker.submit(() -> {});
        try {
            f.get(minutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Still applying the configuration after {} minutes; carrying on", minutes);
        }
    }

    private void saveState(String yaml, String hash, String source, String actor, ConfigRun run) {
        ConfigState st = states.findById(ConfigState.ID).orElseGet(ConfigState::new);
        st.setSource(source);
        st.setEncryptedDocument(encryption.encrypt(yaml));
        st.setDocumentHash(hash);
        st.setAppliedAt(System.currentTimeMillis());
        st.setAppliedBy(actor);
        st.setLastStatus(run.status.name());
        try {
            // A plain mapper: the page expects every list, even an empty one.
            st.setLastRunJson(PLAIN_JSON.writeValueAsString(summary(run, false)));
        } catch (Exception e) {
            st.setLastRunJson(null);
        }
        states.save(st);
    }

    private synchronized void remember(ConfigRun run) {
        runs.addFirst(run);
        while (runs.size() > 20) runs.removeLast();
    }

    public synchronized List<ConfigRun> recentRuns() {
        return List.copyOf(runs);
    }

    public synchronized Optional<ConfigRun> run(String id) {
        return runs.stream().filter(r -> r.id.equals(id)).findFirst();
    }

    public ConfigRun currentRun() {
        return current;
    }

    /** A run for the API. Notes (which may hold generated passwords) only when asked. */
    public static Map<String, Object> summary(ConfigRun run, boolean withNotes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.id);
        m.put("source", run.source);
        m.put("startedBy", run.startedBy);
        m.put("startedAt", run.startedAt);
        m.put("finishedAt", run.finishedAt == 0 ? null : run.finishedAt);
        m.put("status", run.status.name());
        m.put("problems", run.problems);
        m.put("warnings", run.warnings);
        m.put(
                "items",
                run.items.stream()
                        .map(
                                i -> {
                                    Map<String, Object> x = new LinkedHashMap<>();
                                    x.put("section", i.section);
                                    x.put("key", i.key);
                                    x.put("action", i.action.name());
                                    x.put("fields", i.fields);
                                    x.put("label", i.label);
                                    x.put("hostId", i.hostId);
                                    x.put("status", i.status.name());
                                    x.put("message", i.message);
                                    return x;
                                })
                        .toList());
        if (withNotes) m.put("notes", run.notes);
        return m;
    }
}
