package com.wannaverse.service;

import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How to actually reach a Docker host.
 *
 * <ul>
 *   <li>{@code unix://} and plain {@code tcp://}: directly.
 *   <li>{@code tcp://} with TLS: the host's CA, client certificate and key (stored encrypted) are
 *       written to private files that the Docker client and compose read.
 *   <li>{@code ssh://user@server[:port]}: the Docker client library can't speak SSH, so an SSH
 *       tunnel forwards a local socket to the server's Docker socket, and everything (including
 *       compose) talks to that socket. The server's key is trusted the first time and checked from
 *       then on.
 * </ul>
 */
@Service
public class HostConnections {
    private static final Logger log = LoggerFactory.getLogger(HostConnections.class);

    /** ssh://user@server[:port]; neither part may start with "-" (it would read as an option). */
    public static final Pattern SSH_URL =
            Pattern.compile(
                    "^ssh://([A-Za-z0-9_][A-Za-z0-9._-]*)@([A-Za-z0-9][A-Za-z0-9.-]*)(?::([0-9]{1,5}))?$");

    private final DockerHostRepository hostRepository;
    private final EncryptionService encryptionService;
    private final Path dataDir;
    private final Map<String, Process> tunnels = new ConcurrentHashMap<>();

    public HostConnections(
            DockerHostRepository hostRepository,
            EncryptionService encryptionService,
            @Value("${app.data-dir:database}") String dataDir) {
        this.hostRepository = hostRepository;
        this.encryptionService = encryptionService;
        this.dataDir = Path.of(dataDir).toAbsolutePath();
    }

    public static boolean isSsh(String url) {
        return url != null && url.startsWith("ssh://");
    }

    /** The saved host with this URL, if any. */
    public DockerHost hostFor(String url) {
        try {
            return hostRepository.findByDockerHostUrl(url).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** The URL the Docker client should use: the tunnel's socket for ssh://, else as is. */
    public String effectiveUrl(DockerHost host) {
        if (host == null || !isSsh(host.getDockerHostUrl())) {
            return host == null ? null : host.getDockerHostUrl();
        }
        return "unix://" + tunnel(host);
    }

    public String effectiveUrl(String url) {
        if (!isSsh(url)) return url;
        DockerHost host = hostFor(url);
        if (host == null) throw new IllegalStateException("No saved host for " + url);
        return effectiveUrl(host);
    }

    /** Folder with ca.pem, cert.pem and key.pem for a TLS host, or null. */
    public Path tlsDir(DockerHost host) {
        if (host == null || !host.hasTls()) return null;
        String key = host.getId() != null ? host.getId() : "new-" + hash(host.getDockerHostUrl());
        Path dir = dataDir.resolve("tls").resolve(key);
        writePrivate(dir.resolve("ca.pem"), decrypt(host.getTlsCaPem()));
        writePrivate(dir.resolve("cert.pem"), decrypt(host.getTlsCertPem()));
        writePrivate(dir.resolve("key.pem"), decrypt(host.getTlsKeyPem()));
        return dir;
    }

    public Path tlsDir(String url) {
        return tlsDir(hostFor(url));
    }

    /** Stops the tunnel and removes the files kept for a host (on delete or change). */
    public void forget(DockerHost host) {
        if (host == null) return;
        Process p = tunnels.remove(host.getDockerHostUrl());
        if (p != null) p.destroy();
        if (host.getId() != null) deleteTree(dataDir.resolve("tls").resolve(host.getId()));
    }

    // --- SSH tunnels -------------------------------------------------------------------------

    private synchronized String tunnel(DockerHost host) {
        String url = host.getDockerHostUrl();
        Matcher m = SSH_URL.matcher(url);
        if (!m.matches()) throw new IllegalArgumentException("Invalid SSH address: " + url);
        Path socket = Path.of(System.getProperty("java.io.tmpdir"), "dm-ssh", hash(url) + ".sock");
        Process running = tunnels.get(url);
        if (running != null && running.isAlive() && Files.exists(socket)) return socket.toString();
        if (running != null) running.destroy();
        if (host.getSshPrivateKey() == null) {
            throw new IllegalStateException("No SSH key saved for " + url);
        }
        try {
            Files.createDirectories(socket.getParent());
            Files.deleteIfExists(socket);
            Path sshDir = dataDir.resolve("ssh-hosts");
            Path keyFile = sshDir.resolve(hash(url) + ".key");
            writePrivate(keyFile, normalizeKey(decrypt(host.getSshPrivateKey())));
            Path knownHosts = sshDir.resolve("known_hosts");
            if (!Files.exists(knownHosts)) writePrivate(knownHosts, "");

            List<String> command =
                    List.of(
                            "ssh",
                            "-nNT",
                            "-o",
                            "BatchMode=yes",
                            "-o",
                            "ExitOnForwardFailure=yes",
                            "-o",
                            "StrictHostKeyChecking=accept-new",
                            "-o",
                            "UserKnownHostsFile=" + knownHosts,
                            "-o",
                            "IdentitiesOnly=yes",
                            "-o",
                            "ServerAliveInterval=30",
                            "-o",
                            "ServerAliveCountMax=3",
                            "-o",
                            "StreamLocalBindUnlink=yes",
                            "-i",
                            keyFile.toString(),
                            "-p",
                            m.group(3) == null ? "22" : m.group(3),
                            "-L",
                            socket + ":/var/run/docker.sock",
                            "--",
                            m.group(1) + "@" + m.group(2));
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            tunnels.put(url, p);
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (Files.exists(socket)) return socket.toString();
                if (!p.isAlive()) {
                    String out =
                            new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                                    .strip();
                    tunnels.remove(url);
                    throw new IllegalStateException(
                            "SSH to "
                                    + m.group(2)
                                    + " failed: "
                                    + (out.isEmpty() ? "exit " + p.exitValue() : out));
                }
                Thread.sleep(200);
            }
            p.destroy();
            tunnels.remove(url);
            throw new IllegalStateException("SSH to " + m.group(2) + " timed out");
        } catch (IOException e) {
            throw new IllegalStateException("Could not start SSH: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while connecting over SSH");
        }
    }

    /** OpenSSH refuses keys without a final newline, and pasted keys may use Windows line ends. */
    static String normalizeKey(String key) {
        String k = key.replace("\r\n", "\n").strip();
        return k + "\n";
    }

    // --- helpers -----------------------------------------------------------------------------

    private String decrypt(String value) {
        return value == null ? "" : encryptionService.decrypt(value);
    }

    private static void writePrivate(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                Files.setPosixFilePermissions(
                        file.getParent(), PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException notPosix) {
                // Windows during development
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + file + ": " + e.getMessage());
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    static String hash(String s) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
