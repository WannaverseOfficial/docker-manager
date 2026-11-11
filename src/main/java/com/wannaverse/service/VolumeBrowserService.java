package com.wannaverse.service;

import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets admins look inside a Docker volume: list folders, preview and download files, and (when
 * changes are switched on) upload, create folders and delete.
 *
 * <p>Volumes can only be reached through a container, so a small helper container mounts the volume
 * at /v: read-only for browsing, read-write only for changes. Helpers are reused while someone is
 * browsing and removed after a few idle minutes. All paths are checked to stay inside the volume
 * and are passed to commands as arguments, never as part of a shell string.
 */
@Service
public class VolumeBrowserService {
    private static final Logger log = LoggerFactory.getLogger(VolumeBrowserService.class);
    public static final String HELPER_LABEL = "com.wannaverse.helper";
    private static final String HELPER_KIND = "volume-browser";
    private static final String IMAGE = "busybox:stable";
    private static final String ROOT = "/v";
    private static final long IDLE_MS = 5 * 60_000;
    private static final int MAX_ENTRIES = 5000;
    private static final int PREVIEW_BYTES = 512 * 1024;

    private final DockerHostService dockerHostService;
    private final com.wannaverse.persistence.DockerHostRepository hostRepository;

    private record Helper(String hostId, String containerId, long[] lastUsed) {}

    private final Map<String, Helper> helpers = new ConcurrentHashMap<>();

    public VolumeBrowserService(
            DockerHostService dockerHostService,
            com.wannaverse.persistence.DockerHostRepository hostRepository) {
        this.dockerHostService = dockerHostService;
        this.hostRepository = hostRepository;
    }

    public record Entry(
            String name, String type, long size, long modified, String mode, String target) {}

    public record Listing(String path, List<Entry> entries, boolean truncated) {}

    public record Preview(
            String path, long size, boolean binary, boolean truncated, String content) {}

    // --- reading -----------------------------------------------------------------------------

    public Listing list(String hostId, String volume, String path) {
        String p = normalize(path);
        String helper = helper(hostId, volume, false);
        // %F type, %s size, %Y mtime, %A mode, %N quoted name (with -> target for links).
        String script =
                "cd -- \"$1\" || exit 3; for f in * .[!.]* ..?*; do "
                        + "[ -e \"$f\" ] || [ -L \"$f\" ] || continue; "
                        + "stat -c '%F|%s|%Y|%A|%n' -- \"$f\"; done | head -n "
                        + (MAX_ENTRIES + 1);
        var result = api(hostId).exec(helper, "sh", "-c", script, "sh", ROOT + p);
        if (result.exitCode() == 3) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That folder doesn't exist");
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            String[] f = line.split("\\|", 5);
            if (f.length < 5) continue;
            String type =
                    f[0].contains("directory")
                            ? "dir"
                            : f[0].contains("symbolic link")
                                    ? "link"
                                    : f[0].contains("regular") ? "file" : "other";
            String target = null;
            if (type.equals("link")) {
                var t = api(hostId).exec(helper, "readlink", "--", ROOT + join(p, f[4]));
                if (t.ok()) target = t.output().strip();
            }
            entries.add(
                    new Entry(f[4], type, parseLong(f[1]), parseLong(f[2]) * 1000, f[3], target));
        }
        boolean truncated = entries.size() > MAX_ENTRIES;
        if (truncated) entries = entries.subList(0, MAX_ENTRIES);
        entries.sort(
                Comparator.comparing((Entry e) -> !e.type().equals("dir"))
                        .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return new Listing(p, entries, truncated);
    }

    public Preview preview(String hostId, String volume, String path) {
        String p = requireFile(normalize(path));
        String helper = helper(hostId, volume, false);
        try (InputStream in =
                        api(hostId).client().copyArchiveFromContainerCmd(helper, ROOT + p).exec();
                TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null || !entry.isFile()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That's not a file");
            }
            byte[] head = tar.readNBytes(PREVIEW_BYTES);
            boolean binary = looksBinary(head);
            return new Preview(
                    p,
                    entry.getSize(),
                    binary,
                    entry.getSize() > head.length,
                    binary ? null : new String(head, StandardCharsets.UTF_8));
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That file doesn't exist");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't read the file");
        }
    }

    /** What a download is: one file as itself, or a folder as a .tar archive. */
    public record Download(String filename, boolean archive, long size) {}

    public Download describeDownload(String hostId, String volume, String path) {
        String p = normalize(path);
        String helper = helper(hostId, volume, false);
        var stat = api(hostId).exec(helper, "stat", "-c", "%F|%s", "--", ROOT + p);
        if (!stat.ok()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        boolean dir = stat.output().contains("directory");
        String base = p.equals("/") ? volume : p.substring(p.lastIndexOf('/') + 1);
        long size = dir ? -1 : parseLong(stat.output().strip().split("\\|")[1]);
        return new Download(dir ? base + ".tar" : base, dir, size);
    }

    /** Streams a file's bytes, or a folder as a tar archive, to {@code out}. */
    public void download(
            String hostId, String volume, String path, boolean archive, OutputStream out)
            throws IOException {
        String p = normalize(path);
        String helper = helper(hostId, volume, false);
        try (InputStream in =
                api(hostId).client().copyArchiveFromContainerCmd(helper, ROOT + p).exec()) {
            if (archive) {
                in.transferTo(out);
                return;
            }
            try (TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
                TarArchiveEntry entry = tar.getNextEntry();
                if (entry != null && entry.isFile()) tar.transferTo(out);
            }
        }
    }

    // --- changing ----------------------------------------------------------------------------

    /** Writes files into a folder, owned by the folder's owner so the app can still use them. */
    /** One file to upload: its name, its size, and a way to read it. */
    public record Upload(String name, long size, IoSource source) {}

    @FunctionalInterface
    public interface IoSource {
        InputStream open() throws IOException;
    }

    /**
     * Writes files into a folder, owned by the folder's owner so the app can still use them. The
     * upload is streamed through a temporary tar file, so large files don't need to fit in memory.
     */
    public void upload(String hostId, String volume, String dir, List<Upload> files) {
        String p = normalize(dir);
        for (Upload f : files) {
            String name = f.name();
            if (name.isBlank()
                    || name.contains("/")
                    || name.contains("\\")
                    || name.equals(".")
                    || name.equals("..")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid file name: " + name);
            }
        }
        String helper = helper(hostId, volume, true);
        var owner = api(hostId).exec(helper, "stat", "-c", "%u %g", "--", ROOT + p);
        if (!owner.ok())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That folder doesn't exist");
        String[] ids = owner.output().strip().split(" ");
        int uid = (int) parseLong(ids[0]);
        int gid = ids.length > 1 ? (int) parseLong(ids[1]) : uid;

        java.nio.file.Path tarFile = null;
        try {
            tarFile = java.nio.file.Files.createTempFile("dm-upload-", ".tar");
            try (TarArchiveOutputStream tar =
                    new TarArchiveOutputStream(
                            new java.io.BufferedOutputStream(
                                    java.nio.file.Files.newOutputStream(tarFile)))) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                for (Upload f : files) {
                    // Only file entries: no folder entries, which would reset the folder's owner.
                    TarArchiveEntry e = new TarArchiveEntry(f.name());
                    e.setSize(f.size());
                    e.setMode(0100644);
                    e.setUserId(uid);
                    e.setGroupId(gid);
                    e.setModTime(System.currentTimeMillis());
                    tar.putArchiveEntry(e);
                    try (InputStream in = f.source().open()) {
                        in.transferTo(tar);
                    }
                    tar.closeArchiveEntry();
                }
            }
            try (InputStream in =
                    new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(tarFile))) {
                api(hostId)
                        .client()
                        .copyArchiveToContainerCmd(helper)
                        .withRemotePath(ROOT + p)
                        .withTarInputStream(in)
                        .exec();
            }
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Couldn't upload: " + e.getMessage());
        } finally {
            if (tarFile != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tarFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public void createFolder(String hostId, String volume, String dir, String name) {
        String p = normalize(dir);
        if (name == null
                || name.isBlank()
                || name.contains("/")
                || name.contains("\\")
                || name.equals(".")
                || name.equals("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Enter a folder name without slashes");
        }
        String helper = helper(hostId, volume, true);
        String target = ROOT + join(p, name.trim());
        // mkdir, then give it the parent folder's owner.
        var r =
                api(hostId)
                        .exec(
                                helper,
                                "sh",
                                "-c",
                                "mkdir -- \"$1\" && chown \"$(stat -c '%u:%g' -- \"$2\")\" --"
                                        + " \"$1\"",
                                "sh",
                                target,
                                ROOT + p);
        if (!r.ok()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Couldn't create it: " + r.output().strip());
        }
    }

    public void delete(String hostId, String volume, String path) {
        String p = normalize(path);
        if (p.equals("/")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The volume itself can't be deleted here");
        }
        String helper = helper(hostId, volume, true);
        var r = api(hostId).exec(helper, "rm", "-rf", "--", ROOT + p);
        if (!r.ok()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Couldn't delete it: " + r.output().strip());
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private DockerAPI api(String hostId) {
        return dockerHostService.getDockerAPI(hostId);
    }

    /** A running helper with the volume mounted at /v, reused while in use. */
    private synchronized String helper(String hostId, String volume, boolean writable) {
        String key = hostId + "|" + volume + "|" + (writable ? "rw" : "ro");
        Helper h = helpers.get(key);
        DockerAPI api = api(hostId);
        if (h != null) {
            try {
                if (Boolean.TRUE.equals(
                        api.inspectContainer(h.containerId()).getState().getRunning())) {
                    h.lastUsed()[0] = System.currentTimeMillis();
                    return h.containerId();
                }
            } catch (Exception ignored) {
                // gone; make a new one
            }
            helpers.remove(key);
            removeQuietly(api, h.containerId());
        }
        try {
            api.client().inspectVolumeCmd(volume).exec();
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No volume named " + volume);
        }
        try {
            api.inspectImage(IMAGE);
        } catch (NotFoundException e) {
            try {
                api.pullImage(IMAGE);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted");
            }
        }
        String id =
                api.client()
                        .createContainerCmd(IMAGE)
                        .withName("dm-browse-" + UUID.randomUUID().toString().substring(0, 8))
                        .withLabels(
                                Map.of(HELPER_LABEL, HELPER_KIND, "com.wannaverse.volume", volume))
                        .withCmd("sleep", "3600")
                        .withHostConfig(
                                HostConfig.newHostConfig()
                                        .withMounts(
                                                List.of(
                                                        new Mount()
                                                                .withType(MountType.VOLUME)
                                                                .withSource(volume)
                                                                .withTarget(ROOT)
                                                                .withReadOnly(!writable)))
                                        .withNetworkMode("none")
                                        .withAutoRemove(true))
                        .exec()
                        .getId();
        api.startContainer(id);
        helpers.put(key, new Helper(hostId, id, new long[] {System.currentTimeMillis()}));
        return id;
    }

    /** Removes helpers nobody has used for a few minutes. */
    @Scheduled(fixedDelay = 60_000)
    public void removeIdleHelpers() {
        long now = System.currentTimeMillis();
        for (var e : new ArrayList<>(helpers.entrySet())) {
            if (now - e.getValue().lastUsed()[0] > IDLE_MS) {
                helpers.remove(e.getKey());
                try {
                    removeQuietly(api(e.getValue().hostId()), e.getValue().containerId());
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Helpers left over from before a restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void removeLeftoverHelpers() {
        try {
            for (var host : hostRepository.findAll()) {
                try {
                    DockerAPI api = api(host.getId());
                    for (Container c : api.listAllContainers()) {
                        if (c.getLabels() != null
                                && HELPER_KIND.equals(c.getLabels().get(HELPER_LABEL))) {
                            removeQuietly(api, c.getId());
                        }
                    }
                } catch (Exception ignored) {
                    // host unreachable
                }
            }
        } catch (Exception e) {
            log.debug("Could not clean up volume browser helpers: {}", e.getMessage());
        }
    }

    private static void removeQuietly(DockerAPI api, String id) {
        try {
            api.client().removeContainerCmd(id).withForce(true).exec();
        } catch (Exception ignored) {
        }
    }

    /** "/a/b" with no "..", "." or duplicate slashes. Anything escaping the volume is refused. */
    static String normalize(String path) {
        if (path == null || path.isBlank()) return "/";
        if (path.indexOf('\0') >= 0 || path.contains("\n")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Paths can't go above the volume");
            }
            parts.add(part);
        }
        return "/" + String.join("/", parts);
    }

    private static String requireFile(String p) {
        if (p.equals("/"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a file");
        return p;
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** NUL bytes, or too many control characters, mean it isn't text worth showing. */
    static boolean looksBinary(byte[] head) {
        int control = 0;
        int n = Math.min(head.length, 8192);
        for (int i = 0; i < n; i++) {
            int b = head[i] & 0xff;
            if (b == 0) return true;
            if (b < 9 || (b > 13 && b < 32)) control++;
        }
        return n > 0 && control * 10 > n;
    }
}
