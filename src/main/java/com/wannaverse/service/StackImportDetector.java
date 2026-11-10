package com.wannaverse.service;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the compose file of a project that was started outside Docker Manager, so it can be
 * imported without pasting it.
 *
 * <p>Compose records where each project came from in its containers' labels. Those are paths on the
 * Docker host, which Docker Manager (in its own container, possibly on another machine) can't open,
 * so the files are read through a helper container that mounts the folder read-only and is never
 * started: Docker's archive API reads from it directly.
 *
 * <p>When the project has no .env file next to it but the compose file uses ${VARIABLES}, their
 * values are worked out from the running containers (an environment entry {@code POSTGRES_PASSWORD:
 * ${DB_PASSWORD}} and a container with {@code POSTGRES_PASSWORD=s3cret} give {@code
 * DB_PASSWORD=s3cret}).
 */
@Service
public class StackImportDetector {
    private static final Logger log = LoggerFactory.getLogger(StackImportDetector.class);

    static final String WORKING_DIR = "com.docker.compose.project.working_dir";
    static final String CONFIG_FILES = "com.docker.compose.project.config_files";
    static final String ENV_FILES = "com.docker.compose.project.environment_file";
    static final String SERVICE = "com.docker.compose.service";
    private static final String HELPER_IMAGE = "busybox:stable";
    private static final int MAX_FILE = 1 << 20;

    /** ${VAR}, ${VAR:-default}, ${VAR-default}, ${VAR:?error} and $VAR (but not $$, an escape). */
    private static final Pattern VAR =
            Pattern.compile(
                    "(?<!\\$)\\$\\{([A-Za-z_][A-Za-z0-9_]*)((?::?[-?+])[^}]*)?\\}|(?<!\\$)\\$([A-Za-z_][A-Za-z0-9_]*)");

    private final ComposeInspector inspector;

    public StackImportDetector(ComposeInspector inspector) {
        this.inspector = inspector;
    }

    public record Detected(
            String composeContent,
            String envContent,
            String composePath,
            /** Folder relative paths resolve from, or null when it can't be used (see warnings). */
            String projectDir,
            /**
             * "file" (found next to the compose file), "rebuilt" (from the containers) or "none".
             */
            String envSource,
            List<String> rebuiltVariables,
            List<String> missingVariables,
            List<String> services,
            List<String> warnings) {}

    public Detected detect(DockerAPI api, String project, List<Container> containers) {
        Map<String, String> labels = null;
        for (Container c : containers) {
            if (c.getLabels() != null && c.getLabels().get(CONFIG_FILES) != null) {
                labels = c.getLabels();
                break;
            }
        }
        if (labels == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "The containers of "
                            + project
                            + " don't say where their compose file is. Paste it instead.");
        }
        List<String> warnings = new ArrayList<>();
        String workingDir = blankToNull(labels.get(WORKING_DIR));
        List<String> files =
                Arrays.stream(labels.get(CONFIG_FILES).split(","))
                        .map(String::trim)
                        .filter(f -> !f.isEmpty())
                        .toList();
        String composePath = files.get(0);
        if (files.size() > 1) {
            warnings.add(
                    "This project combines "
                            + files.size()
                            + " compose files. Only "
                            + composePath
                            + " was read; merge the others into it before deploying.");
        }

        String[] split = splitPath(composePath);
        String dir = workingDir != null ? workingDir : split[0];
        Found composeFound = readFolder(api, split[0], List.of(split[1]));
        String compose = composeFound.files().get(split[1]);
        // Compose run inside WSL records /mnt/c/... paths, which Docker Desktop itself knows as
        // C:\...; from here on use the form that worked.
        if (!composeFound.folder().equals(split[0]) && wslToWindows(dir) != null) {
            dir = wslToWindows(dir);
        }
        if (compose == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Couldn't read "
                            + composePath
                            + " on the Docker host. It may have been moved or deleted. Paste the"
                            + " compose file instead.");
        }
        Map<String, Object> parsed = inspector.parse(compose);

        // .env: the file compose used, or the default one next to the project.
        String envContent = null;
        String envSource = "none";
        String envLabel = blankToNull(labels.get(ENV_FILES));
        String envPath = envLabel != null ? envLabel.split(",")[0].trim() : null;
        String[] envSplit = envPath != null ? splitPath(envPath) : new String[] {dir, ".env"};
        Map<String, String> envRead = readFolder(api, envSplit[0], List.of(envSplit[1])).files();
        if (envRead.get(envSplit[1]) != null) {
            envContent = envRead.get(envSplit[1]);
            envSource = "file";
        }

        List<String> rebuilt = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Set<String> referenced = variables(compose);
        if (envContent == null && !referenced.isEmpty()) {
            Map<String, String> values = rebuildVariables(api, parsed, containers);
            Set<String> optional = optionalVariables(compose);
            StringBuilder env = new StringBuilder();
            env.append("# Rebuilt by Docker Manager from the running containers.\n");
            for (String v : referenced) {
                if (values.containsKey(v)) {
                    env.append(v).append('=').append(quote(values.get(v))).append('\n');
                    rebuilt.add(v);
                } else if (!optional.contains(v)) {
                    missing.add(v);
                }
            }
            if (!missing.isEmpty()) {
                env.append("\n# Not found in the containers. Set these before deploying.\n");
                for (String v : missing) env.append(v).append("=\n");
            }
            if (!rebuilt.isEmpty() || !missing.isEmpty()) {
                envContent = env.toString();
                envSource = "rebuilt";
            }
        }

        // Relative paths resolve from the original folder, which only works for Linux paths.
        String projectDir = dir;
        boolean windows = dir != null && dir.matches("^[A-Za-z]:[\\\\/].*");
        if (windows) {
            projectDir = null;
            if (usesRelativePaths(parsed)) {
                warnings.add(
                        "It uses paths relative to "
                                + dir
                                + ", a Windows folder that compose (running on Linux) can't"
                                + " use. Change ./ paths to named volumes or absolute paths.");
            }
        }
        warnings.addAll(localFileWarnings(parsed));

        return new Detected(
                compose,
                envContent,
                composePath,
                projectDir,
                envSource,
                rebuilt,
                missing,
                inspector.summarize(compose).services(),
                warnings);
    }

    // --- reading files on the Docker host ----------------------------------------------------

    /**
     * Compose run inside WSL records /mnt/c/... paths, which Docker Desktop knows as C:\....
     * Returns the Windows form, or null for any other path.
     */
    static String wslToWindows(String path) {
        if (path == null) return null;
        Matcher wsl = Pattern.compile("^/mnt/([a-zA-Z])(/.*)?$").matcher(path);
        if (!wsl.matches()) return null;
        return wsl.group(1).toUpperCase(Locale.ROOT)
                + ":"
                + (wsl.group(2) == null ? "\\" : wsl.group(2).replace('/', '\\'));
    }

    /** Files read from a folder, and the folder name that worked on the Docker host. */
    record Found(String folder, Map<String, String> files) {}

    /** Reads files from a folder on the Docker host. Missing files are left out. */
    Found readFolder(DockerAPI api, String folder, List<String> names) {
        Map<String, String> found = readFolderOnce(api, folder, names);
        String win = wslToWindows(folder);
        if (found.isEmpty() && win != null) {
            Map<String, String> viaWindows = readFolderOnce(api, win, names);
            if (!viaWindows.isEmpty()) return new Found(win, viaWindows);
        }
        return new Found(folder, found);
    }

    private Map<String, String> readFolderOnce(DockerAPI api, String folder, List<String> names) {
        Map<String, String> out = new LinkedHashMap<>();
        String id = null;
        try {
            ensureHelperImage(api);
            // A bind *mount* (not -v) fails when the folder is missing instead of creating it.
            id =
                    api.client()
                            .createContainerCmd(HELPER_IMAGE)
                            .withLabels(Map.of("com.wannaverse.helper", "stack-import"))
                            .withHostConfig(
                                    HostConfig.newHostConfig()
                                            .withMounts(
                                                    List.of(
                                                            new Mount()
                                                                    .withType(MountType.BIND)
                                                                    .withSource(folder)
                                                                    .withTarget("/src")
                                                                    .withReadOnly(true)))
                                            .withNetworkMode("none"))
                            .withCmd("true")
                            .exec()
                            .getId();
            for (String name : names) {
                if (name.contains("/") || name.contains("\\") || name.equals("..")) continue;
                String content = readOne(api, id, "/src/" + name);
                if (content != null) out.put(name, content);
            }
        } catch (Exception e) {
            log.debug("Could not read {} on the Docker host: {}", folder, e.getMessage());
        } finally {
            if (id != null) {
                try {
                    api.client().removeContainerCmd(id).withForce(true).exec();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    private static String readOne(DockerAPI api, String containerId, String path) {
        try (InputStream in = api.client().copyArchiveFromContainerCmd(containerId, path).exec();
                TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null || !entry.isFile() || entry.getSize() > MAX_FILE) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            tar.transferTo(buf);
            return buf.toString(StandardCharsets.UTF_8);
        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            log.debug("Could not read {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static void ensureHelperImage(DockerAPI api) throws InterruptedException {
        try {
            api.inspectImage(HELPER_IMAGE);
        } catch (NotFoundException e) {
            api.pullImage(HELPER_IMAGE);
        }
    }

    // --- variables ---------------------------------------------------------------------------

    /** Every variable the compose file refers to, in order of first use. */
    static Set<String> variables(String compose) {
        Set<String> vars = new LinkedHashSet<>();
        Matcher m = VAR.matcher(compose);
        while (m.find()) vars.add(m.group(1) != null ? m.group(1) : m.group(3));
        return vars;
    }

    /** Variables only used with a default (${X:-y}); compose doesn't need them set. */
    static Set<String> optionalVariables(String compose) {
        Set<String> withDefault = new HashSet<>();
        Set<String> without = new HashSet<>();
        Matcher m = VAR.matcher(compose);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(3);
            String op = m.group(2);
            if (op != null && (op.startsWith(":-") || op.startsWith("-"))) withDefault.add(name);
            else without.add(name);
        }
        withDefault.removeAll(without);
        return withDefault;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> rebuildVariables(
            DockerAPI api, Map<String, Object> parsed, List<Container> containers) {
        Map<String, String> values = new LinkedHashMap<>();
        Object servicesObj = parsed.get("services");
        if (!(servicesObj instanceof Map<?, ?> services)) return values;
        for (var entry : ((Map<String, Object>) services).entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> svc)) continue;
            Container container =
                    containers.stream()
                            .filter(
                                    c ->
                                            c.getLabels() != null
                                                    && entry.getKey()
                                                            .equals(c.getLabels().get(SERVICE)))
                            .findFirst()
                            .orElse(null);
            if (container == null) continue;
            InspectContainerResponse inspect;
            try {
                inspect = api.inspectContainer(container.getId());
            } catch (Exception e) {
                continue;
            }
            Map<String, String> actual = new HashMap<>();
            for (String kv :
                    Optional.ofNullable(inspect.getConfig().getEnv()).orElse(new String[0])) {
                int eq = kv.indexOf('=');
                if (eq > 0) actual.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
            for (var env : environment(svc.get("environment")).entrySet()) {
                String value = actual.get(env.getKey());
                if (value != null) match(env.getValue(), value, values);
            }
            if (svc.get("image") instanceof String image
                    && inspect.getConfig().getImage() != null) {
                match(image, inspect.getConfig().getImage(), values);
            }
        }
        return values;
    }

    /** A service's environment, as a list ("K=v") or a map, as key to template. */
    private static Map<String, String> environment(Object env) {
        Map<String, String> out = new LinkedHashMap<>();
        if (env instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        } else if (env instanceof List<?> list) {
            for (Object o : list) {
                String s = String.valueOf(o);
                int eq = s.indexOf('=');
                if (eq > 0) out.put(s.substring(0, eq), s.substring(eq + 1));
            }
        }
        return out;
    }

    /**
     * Matches a template such as "postgres://${USER}:${PASS}@db" against the real value and records
     * what each variable must have been.
     */
    static void match(String template, String actual, Map<String, String> values) {
        Matcher m = VAR.matcher(template);
        StringBuilder regex = new StringBuilder("^");
        List<String> names = new ArrayList<>();
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(template.substring(last, m.start())));
            regex.append("(.*?)");
            names.add(m.group(1) != null ? m.group(1) : m.group(3));
            last = m.end();
        }
        if (names.isEmpty()) return;
        regex.append(Pattern.quote(template.substring(last))).append('$');
        Matcher v = Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(actual);
        if (!v.matches()) return;
        for (int i = 0; i < names.size(); i++) {
            String value = v.group(i + 1);
            if (!value.contains("\n")) values.putIfAbsent(names.get(i), value);
        }
    }

    /** A value for a .env file, quoted when it contains spaces, quotes, # or $. */
    static String quote(String value) {
        if (value.isEmpty() || value.matches("[A-Za-z0-9_./:@%+,=-]+")) return value;
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    // --- warnings ----------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static boolean usesRelativePaths(Map<String, Object> parsed) {
        if (!(parsed.get("services") instanceof Map<?, ?> services)) return false;
        for (Object s : ((Map<String, Object>) services).values()) {
            if (!(s instanceof Map<?, ?> svc)) continue;
            if (svc.get("volumes") instanceof List<?> vols) {
                for (Object v : vols) {
                    String src =
                            v instanceof Map<?, ?> m
                                    ? String.valueOf(m.get("source"))
                                    : String.valueOf(v).split(":", 2)[0];
                    if (src.startsWith(".")) return true;
                }
            }
            if (svc.get("build") != null || svc.get("env_file") != null) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<String> localFileWarnings(Map<String, Object> parsed) {
        List<String> warnings = new ArrayList<>();
        if (!(parsed.get("services") instanceof Map<?, ?> services)) return warnings;
        Set<String> build = new TreeSet<>();
        Set<String> envFile = new TreeSet<>();
        for (var e : ((Map<String, Object>) services).entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> svc)) continue;
            if (svc.get("build") != null) build.add(e.getKey());
            if (svc.get("env_file") != null) envFile.add(e.getKey());
        }
        if (!build.isEmpty()) {
            warnings.add(
                    "Builds its own image ("
                            + String.join(", ", build)
                            + ") from files on the host. Deploying here rebuilds it, which needs"
                            + " the build folder to be readable by Docker Manager; otherwise point"
                            + " the service at a pushed image.");
        }
        if (!envFile.isEmpty()) {
            warnings.add(
                    "Reads settings from env_file ("
                            + String.join(", ", envFile)
                            + "). Compose needs that file when deploying; move its contents into"
                            + " the .env file or the service's environment.");
        }
        return warnings;
    }

    /** Splits a host path (Linux or Windows) into folder and file name. */
    static String[] splitPath(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (i < 0) return new String[] {".", path};
        String dir = i == 0 ? path.substring(0, 1) : path.substring(0, i);
        if (dir.matches("^[A-Za-z]:$")) dir = dir + "\\";
        return new String[] {dir, path.substring(i + 1)};
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
